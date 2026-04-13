package com.architect.gpuchunkgenerator.ast.ir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utilitaire de dump pour la représentation intermédiaire (IR).
 */
public class IrDumper {

    public static void dumpToFile(GpuNode node, String fileName) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DUMP OPTIMIZED GPU IR (GCG Phase 3b) ===\n");
        dumpRecursive(node, 0, sb);
        
        String content = sb.toString();
        tryWrite(Paths.get("run", fileName), content);
        tryWrite(Paths.get(fileName), content);
    }

    private static void tryWrite(Path path, String content) {
        try {
            if (path.getParent() != null && !Files.exists(path.getParent())) return;
            Files.writeString(path, content);
            System.out.println("[GCG] IR dumpé dans : " + path.toAbsolutePath());
        } catch (IOException ignored) {}
    }

    private static void dumpRecursive(GpuNode node, int depth, StringBuilder sb) {
        String indent = "  ".repeat(depth);
        if (node == null) {
            sb.append(indent).append("null\n");
            return;
        }

        switch (node) {
            case GpuNode.Constant c ->
                sb.append(indent).append("- Constant: ").append(c.value()).append("\n");

            case GpuNode.BinaryOp op -> {
                sb.append(indent).append("- BinaryOp: ").append(op.op()).append("\n");
                dumpRecursive(op.left(), depth + 1, sb);
                dumpRecursive(op.right(), depth + 1, sb);
            }

            case GpuNode.UnaryOp unary -> {
                sb.append(indent).append("- UnaryOp: ").append(unary.op()).append("\n");
                dumpRecursive(unary.input(), depth + 1, sb);
            }

            case GpuNode.Noise noise ->
                sb.append(indent).append("- Noise: ").append(noise.name())
                  .append(" (xz=").append(noise.xzScale())
                  .append(", y=").append(noise.yScale())
                  .append(", offset=").append(noise.bufferOffset()).append(")\n");

            case GpuNode.ShiftedNoise sn -> {
                sb.append(indent).append("- ShiftedNoise (xz=").append(sn.xzScale())
                  .append(", y=").append(sn.yScale())
                  .append(", offset=").append(sn.bufferOffset()).append(")\n");
                sb.append(indent).append("  shiftX:\n");
                dumpRecursive(sn.shiftX(), depth + 2, sb);
                sb.append(indent).append("  shiftY:\n");
                dumpRecursive(sn.shiftY(), depth + 2, sb);
                sb.append(indent).append("  shiftZ:\n");
                dumpRecursive(sn.shiftZ(), depth + 2, sb);
            }

            case GpuNode.ShiftNoise shift ->
                sb.append(indent).append("- ShiftNoise: ").append(shift.type())
                  .append(" (offset=").append(shift.bufferOffset()).append(")\n");

            case GpuNode.Spline spline -> {
                sb.append(indent).append("- Spline (id=").append(spline.splineId())
                  .append(", range=[").append(spline.inputMin()).append(", ").append(spline.inputMax()).append("])\n");
                sb.append(indent).append("  coordinate:\n");
                dumpRecursive(spline.coordinate(), depth + 2, sb);
            }

            case GpuNode.HermiteInterpolation hi -> {
                sb.append(indent).append("- HermiteInterpolation (id=").append(hi.splineId())
                  .append(", points=").append(hi.locations().length).append(")\n");
                sb.append(indent).append("  coordinate:\n");
                dumpRecursive(hi.coordinate(), depth + 2, sb);
                for (int i = 0; i < hi.locations().length; i++) {
                    sb.append(indent).append("  Pt[").append(i).append("] (").append(hi.locations()[i])
                      .append(", der: ").append(hi.derivatives()[i]).append("):\n");
                    dumpRecursive(hi.values()[i], depth + 3, sb);
                }
            }

            case GpuNode.YClampedGradient grad ->
                sb.append(indent).append("- YClampedGradient (Y: ").append(grad.fromY())
                  .append("→").append(grad.toY())
                  .append(", V: ").append(grad.fromValue()).append("→").append(grad.toValue()).append(")\n");

            case GpuNode.RangeChoice choice -> {
                sb.append(indent).append("- RangeChoice [").append(choice.minInclusive())
                  .append(", ").append(choice.maxExclusive()).append(")\n");
                sb.append(indent).append("  input:\n");
                dumpRecursive(choice.input(), depth + 2, sb);
                sb.append(indent).append("  whenInRange:\n");
                dumpRecursive(choice.whenInRange(), depth + 2, sb);
                sb.append(indent).append("  whenOutOfRange:\n");
                dumpRecursive(choice.whenOutOfRange(), depth + 2, sb);
            }

            case GpuNode.WeirdScaledSampler wss -> {
                sb.append(indent).append("- WeirdScaledSampler (mapper=").append(wss.mapper())
                  .append(", offset=").append(wss.bufferOffset()).append(")\n");
                sb.append(indent).append("  input:\n");
                dumpRecursive(wss.input(), depth + 2, sb);
            }

            case GpuNode.BlendedNoise bn ->
                sb.append(indent).append("- BlendedNoise (Interpolated Terrain)\n");

            case GpuNode.Reference ref ->
                sb.append(indent).append("- Reference: ").append(ref.functionName()).append("\n");

            case GpuNode.Unknown unknown ->
                sb.append(indent).append("- UNKNOWN: ").append(unknown.originalType()).append("\n");
        }
    }
}
