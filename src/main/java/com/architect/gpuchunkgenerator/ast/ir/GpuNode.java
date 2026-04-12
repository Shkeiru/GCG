package com.architect.gpuchunkgenerator.ast.ir;

/**
 * Représentation intermédiaire (IR) consolidée pour le transpilateur GPU.
 * Chaque type de nœud de l'AST DensityFunction de Minecraft 1.21.1
 * est mappé vers un record interne de cette interface scellée.
 */
public sealed interface GpuNode permits
    GpuNode.Constant,
    GpuNode.BinaryOp,
    GpuNode.UnaryOp,
    GpuNode.Noise,
    GpuNode.ShiftedNoise,
    GpuNode.ShiftNoise,
    GpuNode.Spline,
    GpuNode.YClampedGradient,
    GpuNode.RangeChoice,
    GpuNode.WeirdScaledSampler,
    GpuNode.BlendedNoise,
    GpuNode.Unknown {

    // ========================
    // Constantes et Opérations
    // ========================

    /** Constante scalaire. Source : DensityFunctions.Constant */
    record Constant(double value) implements GpuNode {
        @Override public String toString() { return "Constant(" + value + ")"; }
    }

    /** 
     * Opération binaire (ADD, MUL, MIN, MAX, CLAMP).
     * Source : DensityFunctions.Ap2, MulOrAdd, Clamp 
     */
    record BinaryOp(String op, GpuNode left, GpuNode right) implements GpuNode {}

    /** 
     * Opération unaire (ABS, SQUARE, CUBE, SQUEEZE, HALF_NEGATIVE, QUARTER_NEGATIVE).
     * Source : DensityFunctions.Mapped 
     */
    record UnaryOp(String op, GpuNode input) implements GpuNode {}

    // ========================
    // Bruits (Noise)
    // ========================

    /** 
     * Bruit DoublePerlin simple.
     * Source : DensityFunctions.Noise
     * Formule : noise.getValue(x * xzScale, y * yScale, z * xzScale)
     */
    record Noise(
        String name,
        double xzScale,
        double yScale,
        float globalAmplitude,
        SamplerData firstSampler,
        SamplerData secondSampler,
        int bufferOffset
    ) implements GpuNode {}

    /** 
     * Bruit DoublePerlin avec décalage dynamique par 3 sous-fonctions.
     * Source : DensityFunctions.ShiftedNoise
     * Formule : noise.getValue(x*xzScale + shiftX(pos), y*yScale + shiftY(pos), z*xzScale + shiftZ(pos))
     */
    record ShiftedNoise(
        GpuNode shiftX,
        GpuNode shiftY,
        GpuNode shiftZ,
        double xzScale,
        double yScale,
        int bufferOffset
    ) implements GpuNode {}

    /** 
     * Shifts de bruit (décalages utilisés comme entrées de ShiftedNoise).
     * Source : DensityFunctions.Shift, ShiftA, ShiftB
     * Formules :
     *   SHIFT_XYZ: noise.getValue(x*0.25, y*0.25, z*0.25) * 4.0
     *   SHIFT_A:   noise.getValue(x*0.25, 0.0 ,   z*0.25) * 4.0
     *   SHIFT_B:   noise.getValue(z*0.25, x*0.25,  0.0   ) * 4.0
     */
    record ShiftNoise(ShiftType type, int bufferOffset) implements GpuNode {}

    enum ShiftType { SHIFT_XYZ, SHIFT_A, SHIFT_B }

    // ========================
    // Splines
    // ========================

    /** 
     * Spline cubique cuite en LUT 1D.
     * Source : DensityFunctions.Spline → CubicSpline
     */
    record Spline(
        GpuNode coordinate,
        int splineId,
        float inputMin,
        float inputMax
    ) implements GpuNode {}

    // ========================
    // Nœuds Spécialisés
    // ========================

    /** 
     * Gradient vertical bridé entre deux altitudes.
     * Source : DensityFunctions.YClampedGradient
     * Formule : lerp(clamp((y-fromY)/(toY-fromY), 0, 1), fromValue, toValue)
     */
    record YClampedGradient(
        int fromY,
        int toY,
        double fromValue,
        double toValue
    ) implements GpuNode {}

    /** 
     * Choix conditionnel basé sur une plage de valeurs.
     * Source : DensityFunctions.RangeChoice
     * Formule : (input >= min && input < max) ? whenInRange : whenOutOfRange
     */
    record RangeChoice(
        GpuNode input,
        double minInclusive,
        double maxExclusive,
        GpuNode whenInRange,
        GpuNode whenOutOfRange
    ) implements GpuNode {}

    /** 
     * Échantillonneur de grottes à échelle bizarre.
     * Source : DensityFunctions.WeirdScaledSampler
     * Formule : d = mapper(input); d * abs(noise.getValue(x/d, y/d, z/d))
     */
    record WeirdScaledSampler(
        GpuNode input,
        int bufferOffset,
        RarityMapper mapper
    ) implements GpuNode {}

    enum RarityMapper {
        /** scaleTunnels: {<-0.5: 0.75, <0.0: 1.0, <0.5: 1.5, else: 2.0} */
        TYPE1,
        /** scaleCaves: {<-0.75: 0.5, <-0.5: 0.75, <0.5: 1.0, <0.75: 2.0, else: 3.0} */
        TYPE2
    }

    // ========================
    // Fallback
    // ========================

    /** Bruit interpolé Vanilla (Overworld terrain). */
    record BlendedNoise() implements GpuNode {}

    /** Nœud non reconnu — DOIT être éliminé avant la production. */
    record Unknown(String originalType) implements GpuNode {}

    // ========================
    // Types de données auxiliaires
    // ========================

    /** Données d'un OctavePerlinNoiseSampler pour transfert GPU. */
    record SamplerData(
        int firstOctave,
        float[] amplitudes,
        byte[][] permutations
    ) {}
}
