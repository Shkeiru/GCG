# 🔬 Rapport de Diagnostic — Océan Infini (GPU Worldgen)

## Symptômes Observés

Le monde GPU génère un **océan infini** percé par les carvers, avec une fine couche de deepslate tout en bas. Cela signifie que la densité calculée par le GPU est **systématiquement négative** (ou proche de zéro), ne franchissant le seuil `> 0.0f` que pour les couches de bedrock/deepslate proches de Y=-64.

## Preuve Matérielle : L'IR est mort

Le fichier `overworld_ir_dump.txt` ne contient que **15 lignes** :

```
=== DUMP OPTIMIZED GPU IR (GCG Phase 3b) ===
- MathOp: MIN
  - UnaryOp: SQUEEZE
    - MathOp: MUL
      - Constant: 0.64
      - MathOp: ADD
        - Constant: 0.1171875
        - MathOp: MUL
          - MathOp: ADD
            - Constant: -0.1171875
            - MathOp: ADD
              - Constant: -0.078125
              - MathOp: MUL
                - MathOp: ADD
                  - Constant: 0.078125
```

> [!CAUTION]
> L'arbre a été **entièrement constant-foldé**. Il ne reste **aucun** nœud `GpuNoise`, `GpuSpline`, `GpuYClampedGradient`, ou `GpuUnknown(BlendedNoise)`. Le GPU évalue une **constante fixe** pour chaque position du monde.

---

## Causes Racines Identifiées

### 🔴 Cause 1 : Wrappers Minecraft Non Reconnus (AstOptimizer.java)

L'AST de `finalDensity` dans Minecraft 1.21.1 contient de nombreuses couches de **cache wrappers** que notre optimiseur ne connaît pas. Actuellement reconnus :

| Wrapper | Statut |
|---------|--------|
| `HolderHolder` | ✅ Géré |
| `Marker` | ✅ Géré |
| `BlendDensity` | ✅ Géré |
| `Interpolated` | ✅ Géré |
| `FlatCache` | ✅ Géré |
| `CacheAllInCell` | ✅ Géré |
| `CacheOnce` | ❌ **MANQUANT** |
| `Cache2D` | ❌ **MANQUANT** |
| `WeirdScaledSampler` | ❌ **MANQUANT** (renvoyé comme GpuUnknown → 0.0f) |
| `ShiftedNoise` | ❌ **MANQUANT** (variante de Noise avec shift) |
| `ShiftA` / `ShiftB` | ❌ **MANQUANT** |
| `Slide` | ❌ **MANQUANT** (enveloppe finale de densité) |

Quand un wrapper n'est pas reconnu, il tombe dans le `return new GpuUnknown(className)` à la ligne 160. Le transpileur convertit alors `GpuUnknown` → `"0.0f"`, ce qui **tue** toute la branche de l'arbre.

### 🔴 Cause 2 : Constant Folding Trop Agressif

Le constant folding dans `foldBinary` est **correct mathématiquement** mais **catastrophique fonctionnellement** :
```java
if (left instanceof GpuConstant c1 && right instanceof GpuConstant c2) {
    // Fold immédiatement
}
```
Quand un nœud `Noise` ou `Spline` est converti en `GpuUnknown` → `0.0f` → `GpuConstant(0.0)`, toute opération `MUL(x, 0.0) = 0.0` se propage en cascade, éliminant des branches entières de l'AST.

**Chaîne de mort** : `ShiftedNoise` non reconnu → `GpuUnknown` → `0.0f` → `MUL(Spline, 0.0f)` → `GpuConstant(0.0)` → L'arbre s'effondre.

### 🟠 Cause 3 : Extraction du `coordinate` dans les Splines (AstOptimizer.java:119)

```java
Object coordinateObj = invokeSafe(obj, "coordinate");
```

La méthode `coordinate()` dans `DensityFunctions$Spline` ne renvoie **pas** directement un `DensityFunction`. Dans Minecraft 1.21.1, le `coordinate` est un objet de type `DensityFunctions.Spline.Coordinate` (un wrapper interne), qui encapsule un `DensityFunction$Holder`. Si `invokeSafe` échoue silencieusement, `coordinateNode` devient `GpuConstant(0.0)` — la spline perd totalement sa variable d'entrée.

### 🟠 Cause 4 : Cuisson de Splines Potentiellement à 0 (SplineRegistry.java)

L'évaluation manuelle `evaluateViaHermite` repose sur la récursion des valeurs imbriquées. Si une sous-spline échoue à s'extraire (champ non trouvé, type inattendu), `evaluateValue` retourne `0.0f` via le `catch`, et la LUT entière sera remplie de zéros ou de valeurs aberrantes.

> [!WARNING]
> Il n'y a **aucun log de diagnostic** dans le processus de cuisson. Si les 1024 valeurs sont à 0.0f, nous n'avons aucun moyen de le savoir actuellement.

### 🟡 Cause 5 : Convention de Densité Potentiellement Inversée

Dans Minecraft vanilla, `finalDensity > 0` = **air**, et `finalDensity ≤ 0` = **solide**. Notre Mixin fait :
```java
if (density > 0.0f) {
    chunk.setBlockState(pos, stone, false);  // Solide quand > 0
}
```

Cela est **l'inverse** de la convention Vanilla. Si notre GPU produit des densités correctes (positives = air), nous plaçons de la pierre là où il devrait y avoir de l'air, et vice-versa. Cependant, vu que l'IR est constant-foldé, cette inversion n'est pas la cause première ici — **mais elle le deviendra dès que l'AST sera corrigé**.

---

## Plan de Correction Priorisé

### Priorité 1 : Compléter les Wrappers (AstOptimizer.java)

Ajouter la gestion de **tous** les wrappers courants de Minecraft 1.21.1 :

```java
// Wrappers de cache à traverser :
if (className.equals("CacheOnce") || className.equals("Cache2D")) {
    return visit(invokeSafe(obj, "wrapped"));
}

// Slide (enveloppe finale de densité)
if (className.equals("Slide")) {
    return visit(invokeSafe(obj, "input")); // Simplifié, Slide applique un gradient Y
}

// ShiftedNoise (Noise avec décalage)
if (className.equals("ShiftedNoise")) {
    // Extraire shiftX, shiftY, shiftZ et noise comme un GpuNoise
}

// WeirdScaledSampler
if (className.equals("WeirdScaledSampler")) {
    // C'est un DensityFunction qui modifie le bruit via un facteur
}
```

### Priorité 2 : Ajouter des Logs de Diagnostic

Ajouter un compteur de `GpuUnknown` émis et lister les types manquants pour visualiser l'ampleur de la perte :
```java
// Dans visitInternal, après le return final :
System.err.println("[AstOptimizer] TYPE NON GÉRÉ: " + className + " → 0.0f");
```

### Priorité 3 : Inverser la Convention de Densité (Mixin)

```java
// CORRECT (Convention Vanilla) :
if (density > 0.0f) {
    // AIR (ne rien placer)
} else {
    chunk.setBlockState(pos, stone, false);  // Solide quand ≤ 0
}
```

### Priorité 4 : Ajouter des Logs de Cuisson (SplineRegistry.java)

```java
System.out.println("[SplineRegistry] Spline #" + newId + 
    " : min=" + lut[0] + " max=" + lut[1023] + 
    " range=[" + Arrays.stream(lut).min() + ", " + Arrays.stream(lut).max() + "]");
```

---

## Résumé Exécutif

| Problème | Sévérité | Impact |
|----------|----------|--------|
| Wrappers non reconnus | 🔴 Critique | L'AST est vidé de tout contenu dynamique |
| Constant folding cascade | 🔴 Critique | Propage les 0.0f comme un cancer |
| Coordinate non extrait | 🟠 Haut | Les splines perdent leur axe d'entrée |
| LUT cuites à 0 | 🟠 Haut | Le GPU lit des données corrompues |
| Convention densité inversée | 🟡 Moyen | Terrain inversé une fois l'AST corrigé |

> [!IMPORTANT]
> **La cause racine unique est la Cause 1** : les wrappers manquants dans l'AstOptimizer. Si nous corrigeons cela, les causes 2, 3 et 4 se résoudront naturellement car les nœuds dynamiques (Noise, Spline, Gradient) ne seront plus convertis en constantes.
