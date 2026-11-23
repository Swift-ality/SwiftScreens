import org.lwjgl.util.shaderc.Shaderc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles the pack's core shaders with shaderc (the compiler the 26.x client uses) in every variant the game builds,
 * so a broken shader is found here and not in a player's client.
 *
 *   java -cp lwjgl.jar;lwjgl-natives.jar;lwjgl-shaderc.jar;lwjgl-shaderc-natives.jar tools/ShaderCheck.java
 *        <pack shaders dir> <vanilla shaders dir>
 */
public final class ShaderCheck {
    static Path pack, vanilla;

    public static void main(String[] args) throws Exception {
        pack = Path.of(args[0]);
        vanilla = Path.of(args[1]);
        String[][] variants = {
                {}, {"IS_GRAYSCALE"}, {"IS_GUI"}, {"IS_SEE_THROUGH"},
                {"OIT", "OIT_ACCUMULATE"}, {"OIT", "OIT_ALPHA_ONLY", "OIT_DEPTH_BOUNDS"},
                {"OIT", "OIT_ALPHA_ONLY", "OIT_TRANSMITTANCE"}, {"OIT", "OIT_ALPHA_ONLY", "OIT_ADDITIVE"},
                {"RENDERPEARL_DEPTH_IS_ZERO_TO_ONE"}, {"OIT", "OIT_ACCUMULATE", "RENDERPEARL_DEPTH_IS_ZERO_TO_ONE"},
        };
        int failures = 0;
        for (String file : new String[]{"core/text.vsh", "core/text.fsh", "core/position_tex_color.vsh", "core/position_tex_color.fsh"}) {
            Path src = pack.resolve(file);
            if (!Files.exists(src)) src = vanilla.resolve(file);
            String code = Files.readString(src)
                    .replace("@COLS@", "10").replace("@ROWS@", "6").replace("@FRACTION@", "0.3203125");
            boolean vertex = file.endsWith(".vsh");
            for (String[] defs : file.contains("text") ? variants : new String[][]{{}}) {
                String full = expand(withDefines(code, defs), 0);
                String err = compile(full, vertex, file + " " + String.join(",", defs));
                if (err != null) {
                    failures++;
                    System.out.println("FAIL " + file + " [" + String.join(",", defs) + "]\n" + err);
                }
            }
        }
        System.out.println(failures == 0 ? "all shaders compile" : failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    static String withDefines(String code, String[] defs) {
        StringBuilder d = new StringBuilder();
        for (String s : defs) d.append("#define ").append(s).append('\n');
        // the engine's own values for its transparency shaders
        if (java.util.Arrays.asList(defs).contains("OIT")) d.append("#define OIT_COEFF_COUNT 8\n#define OIT_WAVELET_RANK 3\n#define OIT_COEFF_ATTACHMENT_COUNT 2\n");
        // after #version and #extension lines
        String[] lines = code.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean put = false;
        for (String l : lines) {
            if (!put && !l.startsWith("#version") && !l.startsWith("#extension") && !l.isBlank()) {
                out.append(d);
                put = true;
            }
            out.append(l).append('\n');
        }
        return out.toString();
    }

    static final Pattern INCLUDE = Pattern.compile("^[ \\t]*#include <minecraft:([\\w/]+\\.glsl)>", Pattern.MULTILINE);

    static String expand(String code, int depth) throws Exception {
        if (depth > 8) return code;
        Matcher m = INCLUDE.matcher(code);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Path inc = pack.resolve("include").resolve(m.group(1));
            if (!Files.exists(inc)) inc = vanilla.resolve("include").resolve(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(expand(Files.readString(inc), depth + 1)));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String compile(String code, boolean vertex, String name) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
        Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
        Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
        long result = Shaderc.shaderc_compile_into_spv(compiler, code,
                vertex ? Shaderc.shaderc_glsl_vertex_shader : Shaderc.shaderc_glsl_fragment_shader, name, "main", options);
        String err = Shaderc.shaderc_result_get_compilation_status(result) == Shaderc.shaderc_compilation_status_success
                ? null : Shaderc.shaderc_result_get_error_message(result);
        Shaderc.shaderc_result_release(result);
        Shaderc.shaderc_compile_options_release(options);
        Shaderc.shaderc_compiler_release(compiler);
        return err;
    }
}
