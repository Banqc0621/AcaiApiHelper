package com.hronline.util;

import com.hronline.model.FolderApiStatus;
import com.hronline.model.StarredFolder;
import com.hronline.settings.RestAutoLabSettingsState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestDataExporterFavoritesTest {

    @TempDir
    Path tempDir;

    @Test
    void importsFavoritesLocalFirstAndRemapsFolderCollisions() throws Exception {
        RestAutoLabSettingsState local = new RestAutoLabSettingsState();
        local.saveStarredFolders(List.of(
                folder(StarredFolder.UNCATEGORIZED_ID, StarredFolder.UNCATEGORIZED_NAME, "GET|/local-uncat"),
                folder("shared-local", "Shared", "GET|/local-shared"),
                folder("collision-id", "Local Collision", "GET|/local-collision")
        ));
        local.saveFolderApiParams(new LinkedHashMap<>(Map.of(
                key("shared-local", "GET|/local-shared"), Map.of("source", "local")
        )));
        FolderApiStatus localStatus = status("local-status");
        local.saveFolderApiStatus(new LinkedHashMap<>(Map.of(
                key("shared-local", "GET|/local-shared"), localStatus
        )));
        local.getState().starredApis.add("STALE|/must-be-removed");

        RestAutoLabSettingsState remote = new RestAutoLabSettingsState();
        remote.saveStarredFolders(List.of(
                folder(StarredFolder.UNCATEGORIZED_ID, StarredFolder.UNCATEGORIZED_NAME, "GET|/remote-uncat"),
                folder("remote-shared-id", "Shared", "GET|/local-shared", "GET|/remote-shared"),
                folder("collision-id", "Remote Collision", "GET|/remote-collision"),
                folder("remote-unique", "Unique", "POST|/remote-unique")
        ));
        remote.saveFolderApiParams(new LinkedHashMap<>(Map.of(
                key("remote-shared-id", "GET|/remote-shared"), Map.of("source", "remote"),
                key("remote-shared-id", "GET|/local-shared"), Map.of("source", "remote-must-not-overwrite"),
                key("collision-id", "GET|/remote-collision"), Map.of("token", "remote-token")
        )));
        remote.saveFolderApiStatus(new LinkedHashMap<>(Map.of(
                key("collision-id", "GET|/remote-collision"), status("remote-status")
        )));

        Path exportFile = tempDir.resolve("favorites.json");
        TestDataExporter.exportFavorites(remote, "remote-project", exportFile.toString());
        String json = Files.readString(exportFile, StandardCharsets.UTF_8);
        assertTrue(json.contains(TestDataExporter.FORMAT_FAVORITES));
        assertFalse(json.contains("arkApiKey"), "收藏列表不得携带 AI 凭据");

        String summary = TestDataExporter.importFavorites(local, exportFile.toString());
        assertTrue(summary.contains("新增文件夹 2 个"));

        List<StarredFolder> merged = local.loadStarredFolders();
        assertEquals(5, merged.size());
        assertEquals(merged.size(), merged.stream().map(StarredFolder::getId).distinct().count());
        assertEquals(merged.size(), merged.stream().map(StarredFolder::getName).distinct().count());

        StarredFolder shared = findByName(merged, "Shared");
        assertEquals("shared-local", shared.getId());
        assertEquals(List.of("GET|/local-shared", "GET|/remote-shared"), shared.getApiKeys());

        StarredFolder localCollision = findByName(merged, "Local Collision");
        StarredFolder remoteCollision = findByName(merged, "Remote Collision");
        assertEquals("collision-id", localCollision.getId());
        assertNotEquals("collision-id", remoteCollision.getId());
        assertEquals(List.of("GET|/remote-collision"), remoteCollision.getApiKeys());

        Map<String, Map<String, String>> params = local.loadFolderApiParams();
        assertEquals("local", params.get(key("shared-local", "GET|/local-shared")).get("source"));
        assertEquals("remote", params.get(key("shared-local", "GET|/remote-shared")).get("source"));
        assertEquals("remote-token",
                params.get(key(remoteCollision.getId(), "GET|/remote-collision")).get("token"));
        assertEquals("remote-status", local.loadFolderApiStatus()
                .get(key(remoteCollision.getId(), "GET|/remote-collision")).getMessage());

        Set<String> expectedStarred = new LinkedHashSet<>();
        for (StarredFolder folder : merged) expectedStarred.addAll(folder.getApiKeys());
        assertEquals(expectedStarred, local.getState().starredApis);
        assertFalse(local.getState().starredApis.contains("STALE|/must-be-removed"));
    }

    @Test
    void rejectsWrongFavoritesFormatWithoutChangingLocalData() throws Exception {
        RestAutoLabSettingsState local = new RestAutoLabSettingsState();
        local.saveStarredFolders(List.of(folder("local-id", "Local", "GET|/local")));
        Path invalid = tempDir.resolve("invalid.json");
        Files.writeString(invalid,
                "{\"format\":\"acai-test-data\",\"version\":\"1.0\",\"folders\":[]}",
                StandardCharsets.UTF_8);

        IOException error = assertThrows(IOException.class,
                () -> TestDataExporter.importFavorites(local, invalid.toString()));
        assertTrue(error.getMessage().contains("文件格式不匹配"));
        assertEquals(List.of("GET|/local"), findByName(local.loadStarredFolders(), "Local").getApiKeys());
    }

    private static StarredFolder folder(String id, String name, String... apiKeys) {
        StarredFolder folder = new StarredFolder(id, name);
        folder.setApiKeys(new ArrayList<>(List.of(apiKeys)));
        return folder;
    }

    private static FolderApiStatus status(String message) {
        FolderApiStatus status = FolderApiStatus.untested();
        status.setMessage(message);
        return status;
    }

    private static String key(String folderId, String apiKey) {
        return folderId + "\n" + apiKey;
    }

    private static StarredFolder findByName(List<StarredFolder> folders, String name) {
        return folders.stream()
                .filter(folder -> name.equals(folder.getName()))
                .findFirst()
                .orElseThrow();
    }
}
