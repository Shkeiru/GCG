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
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Mixin pour détourner la génération de terrain vanilla.
 * Remplace le terrain par un monde plat de pierre (CPU Stub).
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    private static final Logger LOGGER = LogUtils.getLogger();
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
            LOGGER.info("DÉMARRAGE de l'initialisation JIT (Overworld détecté)...");
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
                
                // --- APPEL DU DUMP DE SPLINES ---
                LOGGER.info("Extraction de l'anatomie des splines...");
                com.architect.gpuchunkgenerator.ast.SplineDumper.clear();
                // On dumpera toutes les splines rencontrées durant l'optimisation ou spécifiques
                Object surfaceSpline = findFirstSpline(finalDensity);
                if (surfaceSpline != null) {
                    com.architect.gpuchunkgenerator.ast.SplineDumper.dumpSpline(surfaceSpline, 0);
                }
                java.nio.file.Files.writeString(java.nio.file.Paths.get("spline_anatomy_dump.txt"), com.architect.gpuchunkgenerator.ast.SplineDumper.getResult());
                
                // C. L'orchestration Multi-LUT est désormais gérée automatiquement par le SplineRegistry
                // durant la phase d'optimisation de l'AST (Étape A).

                // D. Extraction et Transpilation Biômique (Parité C-E-W)
                com.architect.gpuchunkgenerator.ast.GlslTranspiler transpiler = new com.architect.gpuchunkgenerator.ast.GlslTranspiler();
                
                net.minecraft.world.level.levelgen.DensityFunction rawCont = randomState.router().continents();
                net.minecraft.world.level.levelgen.DensityFunction rawEro = randomState.router().erosion();
                net.minecraft.world.level.levelgen.DensityFunction rawRid = randomState.router().ridges();

                com.architect.gpuchunkgenerator.ast.ir.GpuNode optCont = com.architect.gpuchunkgenerator.ast.AstOptimizer.optimize(rawCont);
                com.architect.gpuchunkgenerator.ast.ir.GpuNode optEro = com.architect.gpuchunkgenerator.ast.AstOptimizer.optimize(rawEro);
                com.architect.gpuchunkgenerator.ast.ir.GpuNode optRid = com.architect.gpuchunkgenerator.ast.AstOptimizer.optimize(rawRid);

                String biomicGlsl = transpiler.transpileAsFunction("get_Continents", optCont) +
                                   transpiler.transpileAsFunction("get_Erosion", optEro) +
                                   transpiler.transpileAsFunction("get_Weirdness", optRid);

                // E. RE-OPTIMISATION de finalDensity avec Mappings Forcés
                // Cela permet d'injecter des appels de fonctions au lieu d'inliner les arbres
                com.architect.gpuchunkgenerator.ast.AstOptimizer.clearForcedMappings();
                com.architect.gpuchunkgenerator.ast.AstOptimizer.registerForcedMapping(rawCont, new com.architect.gpuchunkgenerator.ast.ir.GpuNode.Reference("get_Continents"));
                com.architect.gpuchunkgenerator.ast.AstOptimizer.registerForcedMapping(rawEro, new com.architect.gpuchunkgenerator.ast.ir.GpuNode.Reference("get_Erosion"));
                com.architect.gpuchunkgenerator.ast.AstOptimizer.registerForcedMapping(rawRid, new com.architect.gpuchunkgenerator.ast.ir.GpuNode.Reference("get_Weirdness"));
                
                com.architect.gpuchunkgenerator.ast.ir.GpuNode modularTree = com.architect.gpuchunkgenerator.ast.AstOptimizer.optimize(finalDensity);
                
                // F. Injection des Paramètres de Bruit réels (Injection du "Cerveau" JIT)
                VulkanContext.getInstance().uploadNoiseParameters(com.architect.gpuchunkgenerator.ast.NoiseExtractor.getRegisteredNoises());
                
                String glslCode = transpiler.transpile(modularTree);

                // Sauvegarde de debug du shader généré
                try {
                    String shaderName = "generated_shader.comp";
                    java.nio.file.Files.writeString(java.nio.file.Paths.get(shaderName), glslCode);
                    LOGGER.info("Shader GLSL dumpé dans : {}", shaderName);
                } catch (Exception e) {
                    LOGGER.error("Impossible de dumper le shader", e);
                }
                
                // G. Compilation SPIR-V et Reload Vulkan
                java.nio.ByteBuffer spirv = com.architect.gpuchunkgenerator.vulkan.ShaderCompiler.compileShader(glslCode, org.lwjgl.util.shaderc.Shaderc.shaderc_compute_shader);
                VulkanContext.getInstance().reloadPipeline(spirv);
                
                hasDumpedNoise = true;
                LOGGER.info("Initialisation JIT terminée avec succès !");
            } catch (Exception e) {
                LOGGER.error("ÉCHEC CRITIQUE de l'initialisation JIT", e);
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
                        int worldX = (chunkX << 4) + x;
                        int worldZ = (chunkZ << 4) + z;
                        pos.set(worldX, worldY, worldZ);
                        chunk.setBlockState(pos, stone, false);
                    } else if (worldY < 63) {
                        int worldX = (chunkX << 4) + x;
                        int worldZ = (chunkZ << 4) + z;
                        pos.set(worldX, worldY, worldZ);
                        chunk.setBlockState(pos, water, false);
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
