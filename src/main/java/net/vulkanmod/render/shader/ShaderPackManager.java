package net.vulkanmod.render.shader;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.PipelineManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ShaderPackManager {
    public static final String INTERNAL_PACK = "Internal";

    private static final Path SHADERS_DIR = FabricLoader.getInstance().getGameDir().resolve("shaders");

    private ShaderPackManager() {
    }

    public static void ensureShaderDirectory() {
        try {
            Files.createDirectories(SHADERS_DIR);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create shader directory: " + SHADERS_DIR, e);
        }
    }

    public static Path getShadersDirectory() {
        ensureShaderDirectory();
        return SHADERS_DIR;
    }

    public static String[] getAvailableShaderPacks() {
        ensureShaderDirectory();

        List<String> packs = new ArrayList<>();
        packs.add(INTERNAL_PACK);

        try (var stream = Files.list(SHADERS_DIR)) {
            stream.filter(path -> Files.isDirectory(path) || path.getFileName().toString().endsWith(".zip"))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !name.isBlank())
                    .sorted(Comparator.naturalOrder())
                    .forEach(packs::add);
        } catch (IOException e) {
            Initializer.LOGGER.error("Failed to list shader packs from {}", SHADERS_DIR, e);
        }

        return packs.toArray(String[]::new);
    }

    public static Path getActiveShaderPackRoot() {
        ensureShaderDirectory();

        Path selectedPack = getActiveShaderPackPath();
        if (selectedPack == null || !Files.isDirectory(selectedPack)) {
            return null;
        }

        return selectedPack;
    }

    public static Path getActiveShaderPackPath() {
        ensureShaderDirectory();

        String shaderPack = Initializer.CONFIG.shaderPack;
        if (shaderPack == null || shaderPack.isBlank() || INTERNAL_PACK.equals(shaderPack)) {
            return null;
        }

        Path packPath = SHADERS_DIR.resolve(shaderPack);
        if (!Files.exists(packPath)) {
            return null;
        }

        return packPath;
    }

    public static Path getActiveIncludeDirectory() {
        Path root = getActiveShaderPackRoot();
        if (root == null) {
            return null;
        }

        Path includeDir = root.resolve("include");
        return Files.isDirectory(includeDir) ? includeDir : null;
    }

    public static InputStream openActiveShaderResource(String relativePath) {
        Path packPath = getActiveShaderPackPath();
        if (packPath == null) {
            return null;
        }

        try {
            if (Files.isDirectory(packPath)) {
                Path file = packPath.resolve(relativePath);
                if (!Files.exists(file)) {
                    return null;
                }

                return Files.newInputStream(file);
            }

            if (packPath.getFileName().toString().endsWith(".zip")) {
                try (ZipFile zipFile = new ZipFile(packPath.toFile(), StandardCharsets.UTF_8)) {
                    ZipEntry entry = zipFile.getEntry(relativePath);
                    if (entry == null) {
                        entry = zipFile.stream()
                                .filter(candidate -> !candidate.isDirectory())
                                .filter(candidate -> candidate.getName().equals(relativePath)
                                        || candidate.getName().endsWith("/" + relativePath))
                                .findFirst()
                                .orElse(null);
                    }

                    if (entry == null) {
                        return null;
                    }

                    return new java.io.ByteArrayInputStream(zipFile.getInputStream(entry).readAllBytes());
                }
            }

            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read shader pack resource " + relativePath + " from " + packPath, e);
        }
    }

    public static void reloadActiveShaderPack() {
        PipelineManager.reloadPipelines();

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer != null) {
            minecraft.levelRenderer.allChanged();
        }
    }
}
