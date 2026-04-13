package com.architect.gpuchunkgenerator.vulkan;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;

import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Utilitaire de compilation GLSL -> SPIR-V via Shaderc.
 */
public class ShaderCompiler {

    /**
     * Compile une source GLSL en bytecode SPIR-V.
     */
    public static ByteBuffer compileShader(String source, int shaderKind) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) {
            throw new RuntimeException("Échec de l'initialisation du compilateur Shaderc.");
        }

        long options = shaderc_compile_options_initialize();
        // Optionnel : Configurer les options ici (ex: optimisation, version cible)
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_0);
        shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_size);

        long result = shaderc_compile_into_spv(compiler, source, shaderKind, "main.comp", "main", options);
        if (result == 0) {
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
            throw new RuntimeException("shaderc_compile_into_spv a renvoyé null.");
        }

        if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
            String errorMsg = shaderc_result_get_error_message(result);
            shaderc_result_release(result);
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
            throw new RuntimeException("Erreur de compilation Shaderc :\n" + errorMsg);
        }

        // Récupération des bytes (copie dans un ByteBuffer direct pour Vulkan)
        ByteBuffer spvData = shaderc_result_get_bytes(result);
        if (spvData == null) {
            throw new RuntimeException("Impossible de récupérer le bytecode SPIR-V du résultat.");
        }
        
        // On doit copier les données car le buffer appartient au résultat qui va être libéré
        ByteBuffer copy = MemoryUtil.memAlloc(spvData.remaining());
        copy.put(spvData);
        copy.flip();

        // Nettoyage
        shaderc_result_release(result);
        shaderc_compile_options_release(options);
        shaderc_compiler_release(compiler);

        return copy;
    }
}
