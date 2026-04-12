package com.architect.gpuchunkgenerator.mixin;

import com.architect.gpuchunkgenerator.vulkan.CommandManager;
import com.architect.gpuchunkgenerator.vulkan.VulkanContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Mixin pour détourner la génération de terrain vanilla.
 * Remplace le terrain par un monde plat de pierre (CPU Stub).
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    private static boolean hasDumpedNoise = false;

    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void onFillFromNoise(
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        
        // 1. Garde dimensionnelle (Overworld uniquement)
        if (chunk.getHeight() != 384) {
            return; 
        }

        if (!hasDumpedNoise && randomState != null) {
            System.out.println("[GCG] DÉMARRAGE de l'initialisation JIT (Overworld détecté)...");
            com.architect.gpuchunkgenerator.ast.SplineDumper.clear();
            
            try {
                // A. Extraction et Upload du Bruit
                java.util.List<byte[]> octaves = com.architect.gpuchunkgenerator.ast.NoiseDumper.extractAllPermutations(randomState);
                VulkanContext.getInstance().getCommandManager().uploadNoisePermutations(octaves);
                
                // A. Extraction de l'AST Minecraft
                com.architect.gpuchunkgenerator.ast.NoiseExtractor.clear();
                com.architect.gpuchunkgenerator.ast.SplineRegistry.clear();
                net.minecraft.world.level.levelgen.DensityFunction finalDensity = randomState.router().finalDensity();
                
                // DUMPS (Original AST + Optimized IR)
                com.architect.gpuchunkgenerator.ast.DensityFunctionDumper.dumpToFile(finalDensity, "overworld_ast_dump.txt");
                com.architect.gpuchunkgenerator.ast.DensityFunctionDumper.dumpToFile(randomState.router().continents(), "continents_ast_dump.txt");
                com.architect.gpuchunkgenerator.ast.DensityFunctionDumper.dumpToFile(randomState.router().erosion(), "erosion_ast_dump.txt");
                com.architect.gpuchunkgenerator.ast.DensityFunctionDumper.dumpToFile(randomState.router().ridges(), "ridges_ast_dump.txt");
                
                com.architect.gpuchunkgenerator.ast.ir.GpuNode optimizedTree = com.architect.gpuchunkgenerator.ast.AstOptimizer.optimize(finalDensity);
                com.architect.gpuchunkgenerator.ast.ir.IrDumper.dumpToFile(optimizedTree, "overworld_ir_dump.txt");
                java.nio.file.Files.writeString(java.nio.file.Paths.get("spline_anatomy_dump.txt"), com.architect.gpuchunkgenerator.ast.SplineDumper.getResult());
                
                // C. L'orchestration Multi-LUT est désormais gérée automatiquement par le SplineRegistry
                // durant la phase d'optimisation de l'AST (Étape A).

                // D. Extraction et Transpilation Biômique (Parité C-E-W)
                com.architect.gpuchunkgenerator.ast.GlslTranspiler transpiler = new com.architect.gpuchunkgenerator.ast.GlslTranspiler();
                
                com.architect.gpuchunkgenerator.ast.ir.GpuNode optCont = com.architect.gpuchunkgenerator.ast.AstOptimizer.optimize(randomState.router().continents());
                com.architect.gpuchunkgenerator.ast.ir.GpuNode optEro = com.architect.gpuchunkgenerator.ast.AstOptimizer.optimize(randomState.router().erosion());
                com.architect.gpuchunkgenerator.ast.ir.GpuNode optRid = com.architect.gpuchunkgenerator.ast.AstOptimizer.optimize(randomState.router().ridges());

                String biomicGlsl = transpiler.transpileAsFunction("get_Continents", optCont) +
                                   transpiler.transpileAsFunction("get_Erosion", optEro) +
                                   transpiler.transpileAsFunction("get_Weirdness", optRid);

                // F. Injection des Paramètres de Bruit et Splines réels (Injection du "Cerveau" JIT)
                VulkanContext.getInstance().uploadNoiseParameters(com.architect.gpuchunkgenerator.ast.NoiseExtractor.getRegisteredNoises());
                VulkanContext.getInstance().uploadMultiSplineLuts(com.architect.gpuchunkgenerator.ast.SplineRegistry.getRegisteredSplines());
                
                String mainGlsl = transpiler.transpile(optimizedTree);
                String glslCode = mainGlsl.replace("void main()", biomicGlsl + "\nvoid main()");

                // Sauvegarde de debug du shader généré
                try {
                    String shaderName = "generated_shader.comp";
                    java.nio.file.Files.createDirectories(java.nio.file.Paths.get("run"));
                    java.nio.file.Files.writeString(java.nio.file.Paths.get(shaderName), glslCode);
                    java.nio.file.Files.writeString(java.nio.file.Paths.get("run", shaderName), glslCode);
                    System.out.println("[GCG] Shader GLSL dumpé dans : " + shaderName);
                } catch (Exception e) {
                    System.err.println("[GCG] Impossible de dumper le shader : " + e.getMessage());
                }
                
                // G. Compilation SPIR-V et Reload Vulkan
                java.nio.ByteBuffer spirv = com.architect.gpuchunkgenerator.vulkan.ShaderCompiler.compileShader(glslCode, org.lwjgl.util.shaderc.Shaderc.shaderc_compute_shader);
                VulkanContext.getInstance().reloadPipeline(spirv);
                
                hasDumpedNoise = true;
                System.out.println("[GCG] Initialisation JIT terminée avec succès !");
            } catch (Exception e) {
                System.err.println("[GCG] ÉCHEC CRITIQUE de l'initialisation JIT : " + e.getMessage());
                e.printStackTrace();
            }
        }

        // System.out.println("[Mixin] fillFromNoise appelé pour le chunk " + chunk.getPos());

        int minY = chunk.getMinBuildHeight();
        int height = chunk.getHeight();
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        // Appel au Dispatcher Vulkan (Thread-Safe)
        CommandManager dispatcher = VulkanContext.getInstance().getCommandManager();
        if (dispatcher == null) {
            // Fallback si Vulkan n'est pas initialisé
            return;
        }

        float[] densities = dispatcher.generateChunkDensities(chunkX, chunkZ, minY, height);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        net.minecraft.world.level.block.state.BlockState stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        net.minecraft.world.level.block.state.BlockState water = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();

        // Application des blocs du GPU vers le chunk Minecraft
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < height; y++) {
                    float density = densities[(x * height * 16) + (z * height) + y];
                    int worldY = minY + y;
                    
                    if (density > 0.0f) {
                        if (worldY < 63) {
                            int worldX = (chunkX << 4) + x;
                            int worldZ = (chunkZ << 4) + z;
                            pos.set(worldX, worldY, worldZ);
                            chunk.setBlockState(pos, water, false);
                        }
                    } else {
                        int worldX = (chunkX << 4) + x;
                        int worldZ = (chunkZ << 4) + z;
                        pos.set(worldX, worldY, worldZ);
                        chunk.setBlockState(pos, stone, false);
                    }
                }
            }
        }

        cir.setReturnValue(CompletableFuture.completedFuture(chunk));
    }

    private Object findFirstSpline(Object obj) {
        if (obj == null) return null;
        String className = obj.getClass().getSimpleName();
        if (className.equals("Spline")) {
            try {
                java.lang.reflect.Method m = obj.getClass().getDeclaredMethod("spline");
                m.setAccessible(true);
                return m.invoke(obj);
            } catch (Exception e) {
                return null;
            }
        }
        
        // Recherche récursive dans les champs (input, argument1, etc.)
        for (java.lang.reflect.Method m : obj.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && (m.getReturnType().getSimpleName().equals("DensityFunction") || m.getReturnType().getSimpleName().equals("Holder"))) {
                try {
                    m.setAccessible(true);
                    Object nested = m.invoke(obj);
                    if (nested instanceof net.minecraft.core.Holder<?> h) {
                        if (h.isBound()) nested = h.value();
                        else continue;
                    }
                    Object found = findFirstSpline(nested);
                    if (found != null) return found;
                } catch (Exception e) {}
            }
        }
        return null;
    }
}
