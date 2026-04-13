package com.architect.gpuchunkgenerator.ast;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import com.architect.gpuchunkgenerator.ast.ir.GpuNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registre central pour la gestion des Splines Minecraft.
 * Désormais utilisé pour identifier les splines uniques et leur attribuer des IDs de fonctions GLSL.
 * La "cuisson" LUT 1D a été supprimée au profit d'une expansion AST dynamique.
 */
public class SplineRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<Object, Integer> SPLINE_TO_ID = new HashMap<>();
    private static final List<GpuNode.HermiteInterpolation> REGISTERED_NODES = new ArrayList<>();
    
    /**
     * Enregistre une spline Multipoint pour la génération d'une fonction GLSL.
     */
    public static int registerSplineFunction(Object splineObject, GpuNode.HermiteInterpolation node) {
        if (SPLINE_TO_ID.containsKey(splineObject)) {
            return SPLINE_TO_ID.get(splineObject);
        }

        int id = REGISTERED_NODES.size();
        SPLINE_TO_ID.put(splineObject, id);
        
        // On stocke le nœud final (avec l'ID correct) pour le transpilateur
        GpuNode.HermiteInterpolation nodeWithId = new GpuNode.HermiteInterpolation(
            node.coordinate(),
            node.locations(),
            node.values(),
            node.derivatives(),
            id
        );
        REGISTERED_NODES.add(nodeWithId);
        
        LOGGER.info("Spline enregistrée pour GLSL : ID #{} ({} points)", id, node.locations().length);
        return id;
    }

    public static List<GpuNode.HermiteInterpolation> getRegisteredNodes() {
        return REGISTERED_NODES;
    }

    public static void clear() {
        SPLINE_TO_ID.clear();
        REGISTERED_NODES.clear();
    }
    
    // Pour la compatibilité temporaire durant la transition si nécessaire
    public static List<?> getRegisteredSplines() {
        return List.of(); 
    }
}
