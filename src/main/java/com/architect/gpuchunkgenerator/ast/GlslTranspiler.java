package com.architect.gpuchunkgenerator.ast;

import com.architect.gpuchunkgenerator.ast.ir.GpuNode;
import java.util.Locale;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Moteur de transpilation : Transforme l'IR GpuNode en code source GLSL (SPIR-V 450).
 * Supporte tous les types de nœuds de la cartographie DensityFunction 1.21.1.
 */
public class GlslTranspiler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private boolean isFunctionalMode = false;
    private final java.util.Map<String, String> customFunctions = new java.util.LinkedHashMap<>();

    private static final String SHADER_TEMPLATE = """
#version 450

layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

layout(set = 0, binding = 0, std430) buffer OutputBuffer { float densities[]; };
layout(set = 0, binding = 1, std430) readonly buffer NoiseBuffer { int permutations[]; };
layout(set = 0, binding = 2, std430) readonly buffer Unused1 { float u1[]; };
layout(set = 0, binding = 3, std430) readonly buffer NoiseParameters { float data[]; } noiseParams;
layout(set = 0, binding = 4, std430) readonly buffer Unused2 { float u2[]; };

layout(push_constant) uniform PushConstants {
    int chunkX;
    int chunkZ;
    int minY;
    int height;
    int maxIndex;
} pc;

// ==============================
// Fonctions Perlin (parité 1:1)
// ==============================

float fade(float t) {
    return t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f);
}

float lerp(float t, float a, float b) {
    return a + t * (b - a);
}

float grad(int hash, float x, float y, float z) {
    int h = hash & 15;
    float u = h < 8 ? x : y;
    float v = h < 4 ? y : (h == 12 || h == 14) ? x : z;
    return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
}

int get_perm(int i, int perm_offset) {
    return floatBitsToInt(noiseParams.data[perm_offset + (i & 255)]);
}

float single_perlin(vec3 pos, int perm_offset) {
    int i = int(floor(pos.x));
    int j = int(floor(pos.y));
    int k = int(floor(pos.z));

    float dx = pos.x - float(i);
    float dy = pos.y - float(j);
    float dz = pos.z - float(k);

    float fx = fade(dx);
    float fy = fade(dy);
    float fz = fade(dz);

    int A = get_perm(i, perm_offset) + j;
    int AA = get_perm(A, perm_offset) + k;
    int AB = get_perm(A + 1, perm_offset) + k;
    int B = get_perm(i + 1, perm_offset) + j;
    int BA = get_perm(B, perm_offset) + k;
    int BB = get_perm(B + 1, perm_offset) + k;

    return lerp(fz,
        lerp(fy,
            lerp(fx, grad(get_perm(AA, perm_offset), dx, dy, dz), grad(get_perm(BA, perm_offset), dx - 1.0f, dy, dz)),
            lerp(fx, grad(get_perm(AB, perm_offset), dx, dy - 1.0f, dz), grad(get_perm(BB, perm_offset), dx - 1.0f, dy - 1.0f, dz))
        ),
        lerp(fy,
            lerp(fx, grad(get_perm(AA + 1, perm_offset), dx, dy, dz - 1.0f), grad(get_perm(BA + 1, perm_offset), dx - 1.0f, dy, dz - 1.0f)),
            lerp(fx, grad(get_perm(AB + 1, perm_offset), dx, dy - 1.0f, dz - 1.0f), grad(get_perm(BB + 1, perm_offset), dx - 1.0f, dy - 1.0f, dz - 1.0f))
        )
    );
}

// ==============================
// OctavePerlin + DoublePerlin
// ==============================

float sample_octave_perlin(vec3 pos, int base_offset, int sampler_id) {
    int n1 = floatBitsToInt(noiseParams.data[base_offset + 2]);
    int n2 = floatBitsToInt(noiseParams.data[base_offset + 4]);

    int first_octave = (sampler_id == 0) ? floatBitsToInt(noiseParams.data[base_offset + 1])
                                         : floatBitsToInt(noiseParams.data[base_offset + 3]);
    int num_octaves = (sampler_id == 0) ? n1 : n2;

    int amp_offset = base_offset + 5 + (sampler_id == 0 ? 0 : n1);
    int perm_start = base_offset + 5 + n1 + n2 + (sampler_id == 0 ? 0 : n1 * 512);

    float total = 0.0f;
    float freq = pow(2.0, float(first_octave));

    for (int i = 0; i < num_octaves; i++) {
        float amp = noiseParams.data[amp_offset + i];
        if (amp != 0.0) {
            total += single_perlin(pos * freq, perm_start + (i * 512)) * amp;
        }
        freq *= 2.0;
    }
    return total;
}

float sample_double_perlin(vec3 pos, int base_offset) {
    float global_amp = noiseParams.data[base_offset];
    float val1 = sample_octave_perlin(pos, base_offset, 0);
    float val2 = sample_octave_perlin(pos, base_offset, 1);
    return (val1 + val2) * global_amp;
}

// ==============================
// Fonctions utilitaires Minecraft
// ==============================

float squeeze(float x) {
    float f = clamp(x, -1.0f, 1.0f);
    return (f / 2.0f) - ((f * f * f) / 24.0f);
}

float yClampedGradient(float y, float fromY, float toY, float fromValue, float toValue) {
    float t = clamp((y - fromY) / (toY - fromY), 0.0f, 1.0f);
    return t * (toValue - fromValue) + fromValue;
}

// ==============================
// WeirdScaledSampler (CaveScaler)
// ==============================

float scaleTunnels(float v) {
    if (v < -0.5) return 0.75;
    else if (v < 0.0) return 1.0;
    else if (v < 0.5) return 1.5;
    else return 2.0;
}

float scaleCaves(float v) {
    if (v < -0.75) return 0.5;
    else if (v < -0.5) return 0.75;
    else if (v < 0.5) return 1.0;
    else if (v < 0.75) return 2.0;
    else return 3.0;
}

// ==============================
// Spline Multi-LUT
// ==============================

// Fonction d'interpolation d'Hermite générique utilisée par les splines générées
float hermite_interp(float t, float y0, float y1, float d0, float d1, float h) {
    float a = d0 * h - (y1 - y0);
    float b = -d1 * h + (y1 - y0);
    return (y0 + t * (y1 - y0)) + t * (1.0 - t) * (a + t * (b - a));
}

// ==============================
// Legacy (fallback)
// ==============================

float perlinNoiseLegacy(float x, float y, float z, int octaveOffset) {
    int i = int(floor(x));
    int j = int(floor(y));
    int k = int(floor(z));
    float dx = x - float(i); float dy = y - float(j); float dz = z - float(k);
    float fx = fade(dx); float fy = fade(dy); float fz = fade(dz);
    int A = permutations[octaveOffset + (i & 255)] + j;
    int AA = permutations[octaveOffset + (A & 255)] + k;
    int AB = permutations[octaveOffset + (A + 1 & 255)] + k;
    int B = permutations[octaveOffset + (i + 1 & 255)] + j;
    int BA = permutations[octaveOffset + (B & 255)] + k;
    int BB = permutations[octaveOffset + (B + 1 & 255)] + k;
    return lerp(fz,
        lerp(fy,
            lerp(fx, grad(permutations[octaveOffset + (AA & 255)], dx, dy, dz), grad(permutations[octaveOffset + (BA & 255)], dx - 1.0f, dy, dz)),
            lerp(fx, grad(permutations[octaveOffset + (AB & 255)], dx, dy - 1.0f, dz), grad(permutations[octaveOffset + (BB & 255)], dx - 1.0f, dy - 1.0f, dz))
        ),
        lerp(fy,
            lerp(fx, grad(permutations[octaveOffset + (AA + 1 & 255)], dx, dy, dz - 1.0f), grad(permutations[octaveOffset + (BA + 1 & 255)], dx - 1.0f, dy, dz - 1.0f)),
            lerp(fx, grad(permutations[octaveOffset + (AB + 1 & 255)], dx, dy - 1.0f, dz - 1.0f), grad(permutations[octaveOffset + (BB + 1 & 255)], dx - 1.0f, dy - 1.0f, dz - 1.0f))
        )
    );
}

float blendedNoise(float globalX, float globalY, float globalZ) {
    float scaleXZ = 0.0146f;
    float scaleY = 0.01f;
    float nx = globalX * scaleXZ; float ny = globalY * scaleY; float nz = globalZ * scaleXZ;
    float total = 0.0; float freq = 1.0; float amp = 1.0;
    for(int i=0; i<8; i++) {
        total += perlinNoiseLegacy(nx*freq, ny*freq, nz*freq, i*512) * amp;
        freq *= 2.0; amp *= 0.5;
    }
    return total * 20.0f;
}

/*#FUNCTIONS#*/

// ==============================
// Main
// ==============================

void main() {
    uint x = gl_GlobalInvocationID.x;
    uint z = gl_GlobalInvocationID.y;
    if (x >= 16 || z >= 16) return;

    float globalX = float((pc.chunkX * 16) + int(x));
    float globalZ = float((pc.chunkZ * 16) + int(z));

    for (int localY = 0; localY < pc.height; localY++) {
        float absoluteY = float(pc.minY + localY);
        uint arrayIndex = (x * uint(pc.height) * 16) + (z * uint(pc.height)) + uint(localY);
        if (arrayIndex >= uint(pc.maxIndex)) continue;

        float finalDensity = /*#EXPRESSION#*/;
        densities[arrayIndex] = finalDensity;
    }
}
""";

    public String transpile(GpuNode root) {
        this.isFunctionalMode = false;
        LOGGER.info("Début de l'expression principale...");
        
        // 1. Génération de l'expression principale
        String expression = buildExpression(root);
        
        // 2. Génération des fonctions de splines
        StringBuilder functions = new StringBuilder();
        this.isFunctionalMode = true;
        try {
            // Forward declarations
            for (com.architect.gpuchunkgenerator.ast.ir.GpuNode.HermiteInterpolation node : SplineRegistry.getRegisteredNodes()) {
                functions.append(String.format(Locale.US, "float eval_spline_%d(float x, float globalX, float globalY, float globalZ);\n", node.splineId()));
            }
            functions.append("\n");

            for (com.architect.gpuchunkgenerator.ast.ir.GpuNode.HermiteInterpolation node : SplineRegistry.getRegisteredNodes()) {
                functions.append(generateSplineFunction(node));
            }
        } finally {
            this.isFunctionalMode = false;
        }
        
        StringBuilder customFuncsStr = new StringBuilder();
        for (String code : this.customFunctions.values()) {
            customFuncsStr.append(code).append("\n");
        }
        
        String shader = SHADER_TEMPLATE;
        shader = shader.replace("/*#FUNCTIONS#*/", customFuncsStr.toString() + "\n" + functions.toString());
        shader = shader.replace("/*#EXPRESSION#*/", expression);
        
        return shader;
    }

    private String generateSplineFunction(GpuNode.HermiteInterpolation node) {
        StringBuilder sb = new StringBuilder();
        int n = node.locations().length;
        
        sb.append(String.format(Locale.US, "float eval_spline_%d(float x, float globalX, float globalY, float globalZ) {\n", node.splineId()));
        
        // Clamping & Extrapolation
        sb.append(String.format(Locale.US, "    if (x <= %.5ff) return %s + %.5ff * (x - %.5ff);\n", 
            node.locations()[0], buildExpression(node.values()[0]), node.derivatives()[0], node.locations()[0]));
        
        sb.append(String.format(Locale.US, "    if (x >= %.5ff) return %s + %.5ff * (x - %.5ff);\n", 
            node.locations()[n-1], buildExpression(node.values()[n-1]), node.derivatives()[n-1], node.locations()[n-1]));
        
        // Interval search (Binary search would be better for > 8 points, but if-else is fine for now)
        for (int i = 0; i < n - 1; i++) {
            float x0 = node.locations()[i];
            float x1 = node.locations()[i+1];
            String cond = (i == n - 2) ? "" : String.format(Locale.US, "if (x < %.5ff) ", x1);
            
            sb.append("    ").append(cond).append("{\n");
            sb.append(String.format(Locale.US, "        float x0 = %.5ff; float x1 = %.5ff; float h = x1 - x0;\n", x0, x1));
            sb.append("        float t = (x - x0) / h;\n");
            sb.append(String.format(Locale.US, "        float y0 = %s; float y1 = %s;\n", buildExpression(node.values()[i]), buildExpression(node.values()[i+1])));
            sb.append(String.format(Locale.US, "        return hermite_interp(t, y0, y1, %.5ff, %.5ff, h);\n", node.derivatives()[i], node.derivatives()[i+1]));
            sb.append("    }\n");
        }
        
        sb.append("}\n\n");
        return sb.toString();
    }

    public String transpileAsFunction(String functionName, GpuNode root) {
        this.isFunctionalMode = true;
        try {
            String expression = buildExpression(root);
            String funcCode = String.format(Locale.US,
                "float %s(float globalX, float globalY, float globalZ) {\n    return %s;\n}\n",
                functionName, expression);
            this.customFunctions.put(functionName, funcCode);
            return funcCode;
        } finally {
            this.isFunctionalMode = false;
        }
    }

    private String buildExpression(GpuNode node) {
        if (node == null) return "0.0f";

        return switch (node) {
            case GpuNode.Constant c -> fmt(c.value());

            case GpuNode.BinaryOp op -> {
                String a = buildExpression(op.left());
                String b = buildExpression(op.right());
                yield switch (op.op()) {
                    case "ADD" -> "(" + a + " + " + b + ")";
                    case "MUL" -> "(" + a + " * " + b + ")";
                    case "MIN" -> "min(" + a + ", " + b + ")";
                    case "MAX" -> "max(" + a + ", " + b + ")";
                    case "CLAMP" -> {
                        if (op.right() instanceof GpuNode.BinaryOp range && range.op().equals("RANGE")) {
                            yield "clamp(" + a + ", " + buildExpression(range.left()) + ", " + buildExpression(range.right()) + ")";
                        }
                        yield "clamp(" + a + ", 0.0f, 1.0f)";
                    }
                    default -> "0.0f";
                };
            }

            case GpuNode.UnaryOp u -> {
                String input = buildExpression(u.input());
                yield switch (u.op()) {
                    case "ABS" -> "abs(" + input + ")";
                    case "SQUARE" -> "(" + input + " * " + input + ")";
                    case "CUBE" -> {
                        // Avoid evaluating input 3 times in generated code
                        yield "(" + input + " * " + input + " * " + input + ")";
                    }
                    case "SQUEEZE" -> "squeeze(" + input + ")";
                    case "HALF_NEGATIVE" -> "(" + input + " > 0.0f ? " + input + " : " + input + " * 0.5f)";
                    case "QUARTER_NEGATIVE" -> "(" + input + " > 0.0f ? " + input + " : " + input + " * 0.25f)";
                    default -> input;
                };
            }

            case GpuNode.Noise noise -> {
                String pos = "vec3(" + gx() + " * " + fmt(noise.xzScale()) + ", "
                                     + gy() + " * " + fmt(noise.yScale()) + ", "
                                     + gz() + " * " + fmt(noise.xzScale()) + ")";
                yield "sample_double_perlin(" + pos + ", " + noise.bufferOffset() + ")";
            }

            case GpuNode.ShiftedNoise sn -> {
                String sx = buildExpression(sn.shiftX());
                String sy = buildExpression(sn.shiftY());
                String sz = buildExpression(sn.shiftZ());
                String pos = "vec3(" + gx() + " * " + fmt(sn.xzScale()) + " + " + sx + ", "
                                     + gy() + " * " + fmt(sn.yScale())  + " + " + sy + ", "
                                     + gz() + " * " + fmt(sn.xzScale()) + " + " + sz + ")";
                yield "sample_double_perlin(" + pos + ", " + sn.bufferOffset() + ")";
            }

            case GpuNode.ShiftNoise shift -> {
                String pos = switch (shift.type()) {
                    case SHIFT_XYZ -> "vec3(" + gx() + " * 0.25f, " + gy() + " * 0.25f, " + gz() + " * 0.25f)";
                    case SHIFT_A   -> "vec3(" + gx() + " * 0.25f, 0.0f, " + gz() + " * 0.25f)";
                    case SHIFT_B   -> "vec3(" + gz() + " * 0.25f, " + gx() + " * 0.25f, 0.0f)";
                };
                yield "(sample_double_perlin(" + pos + ", " + shift.bufferOffset() + ") * 4.0f)";
            }

            case GpuNode.Spline spline -> {
                yield "/* Legacy Spline Bypassed */ 0.0f";
            }

            case GpuNode.HermiteInterpolation hi -> {
                String coord = buildExpression(hi.coordinate());
                yield String.format(Locale.US, "eval_spline_%d(%s, %s, %s, %s)", hi.splineId(), coord, gx(), gy(), gz());
            }

            case GpuNode.YClampedGradient grad ->
                "yClampedGradient(" + gy() + ", " + fmt(grad.fromY()) + ", " + fmt(grad.toY())
                + ", " + fmt(grad.fromValue()) + ", " + fmt(grad.toValue()) + ")";

            case GpuNode.RangeChoice choice -> {
                String inExpr = buildExpression(choice.input());
                yield "((" + inExpr + " >= " + fmt(choice.minInclusive())
                    + " && " + inExpr + " < " + fmt(choice.maxExclusive()) + ") ? "
                    + buildExpression(choice.whenInRange()) + " : "
                    + buildExpression(choice.whenOutOfRange()) + ")";
            }

            case GpuNode.WeirdScaledSampler wss -> {
                String inputExpr = buildExpression(wss.input());
                String scalerFunc = wss.mapper() == GpuNode.RarityMapper.TYPE1 ? "scaleTunnels" : "scaleCaves";
                // We need a temp variable, but we're in expression mode.
                // Use a nested expression with the scaler evaluated inline.
                // d = scaler(input); result = d * abs(noise(x/d, y/d, z/d))
                // Since GLSL doesn't have let-in expressions, we use a lambda-like trick:
                // Actually we must inline it. The scaler is cheap (branchy but simple).
                String d = scalerFunc + "(" + inputExpr + ")";
                String pos = "vec3(" + gx() + " / " + d + ", " + gy() + " / " + d + ", " + gz() + " / " + d + ")";
                yield "(" + d + " * abs(sample_double_perlin(" + pos + ", " + wss.bufferOffset() + ")))";
            }

            case GpuNode.BlendedNoise bn -> {
                yield "blendedNoise(" + gx() + ", " + gy() + ", " + gz() + ")";
            }

            case GpuNode.Reference ref -> {
                yield ref.functionName() + "(" + gx() + ", " + gy() + ", " + gz() + ")";
            }

            case GpuNode.Unknown unknown -> {
                String type = unknown.originalType();
                LOGGER.error("Type inconnu: {} → 0.0f", type);
                yield "/* ERREUR: NOEUD INCONNU " + type + " */ 0.0f";
            }
        };
    }

    // ================================================================
    // Helpers pour les coordonnées selon le mode
    // ================================================================

    private String gx() { return isFunctionalMode ? "globalX" : "globalX"; }
    private String gy() { return isFunctionalMode ? "globalY" : "absoluteY"; }
    private String gz() { return isFunctionalMode ? "globalZ" : "globalZ"; }

    private String fmt(double val) {
        return String.format(Locale.US, "%.5ff", val);
    }
}
