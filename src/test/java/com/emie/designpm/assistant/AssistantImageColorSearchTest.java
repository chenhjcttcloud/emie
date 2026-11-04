package com.emie.designpm.assistant;

import static com.emie.designpm.assistant.AssistantImageColorSearch.ColorFamily.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.file.service.FileThumbnailService;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssistantImageColorSearchTest {
    @TempDir
    Path uploadRoot;

    private final FileThumbnailService thumbnails = mock(FileThumbnailService.class);
    private AssistantImageColorSearch colors;
    private Path cacheRoot;

    @BeforeEach
    void setup() throws Exception {
        cacheRoot = Files.createDirectories(uploadRoot.resolve("thumbnail-cache"));
        colors = new AssistantImageColorSearch(thumbnails, uploadRoot.toString());
    }

    @Test
    void understandsColorDescriptionsAndDoesNotReadNegatedColorsAsPositive() {
        assertEquals(List.of(GREEN), colors.parseColors("帮我找绿色系的图档"));
        assertEquals(List.of(GREEN), colors.parseColors("薄荷绿和森林绿的包装"));
        assertEquals(List.of(GREEN), colors.parseColors("mint green artwork"));
        assertEquals(List.of(RED, GREEN), colors.parseColors("红色和绿色搭配"));
        assertEquals(List.of(PINK), colors.parseColors("粉红图案"));
        assertEquals(List.of(PINK), colors.parseColors("粉红色图案"));
        assertEquals(List.of(CYAN), colors.parseColors("青绿的图档"));
        assertEquals(List.of(CYAN), colors.parseColors("蓝绿色图档"));
        assertEquals(List.of(BLUE), colors.parseColors("不要绿色，找蓝色图档"));
        assertEquals(List.of(), colors.parseColors("不想要绿色和蓝色"));
        assertEquals(List.of(), colors.parseColors("without green or blue"));
        assertEquals(List.of(), colors.parseColors("preferred artwork"));
        for (String name : List.of("小红帽", "白雪公主", "黑猫警长", "蜡笔小新")) {
            assertEquals(List.of(), colors.parseColors("查找" + name + "图档"), name);
            assertEquals("查找" + name + "图档", colors.textWithoutColors("查找" + name + "图档"));
        }
        assertEquals(List.of(GREEN), colors.parseColors("白雪公主绿色系"));
        assertEquals("的图档", colors.textWithoutColors("绿色系的图档"));
        assertEquals("KT 的图档", colors.textWithoutColors("KT 薄荷绿色系的图档"));
        assertEquals("KT图档", colors.textWithoutColors("KT图档"));
        assertEquals("不要绿色", colors.textWithoutColors("不要绿色"));
    }

    @Test
    void returnsActualGreenThumbnailAndRejectsRedOrTinyGreenAccent() throws Exception {
        BufferedImage green = canvas(640, 400, Color.WHITE);
        fill(green, 128, 80, 384, 240, new Color(48, 170, 82));
        thumbnail("green.ai", green);
        thumbnail("red.png", canvas(640, 400, Color.RED));
        BufferedImage dot = canvas(640, 400, Color.WHITE);
        fill(dot, 10, 10, 20, 20, Color.GREEN);
        thumbnail("dot.png", dot);

        var match = colors.match("green.ai", List.of(GREEN));
        assertTrue(match.score() > .8);
        assertTrue(match.available());
        assertTrue(match.reasons().getFirst().contains("绿色"));
        assertEquals(0, colors.match("red.png", List.of(GREEN)).score());
        assertTrue(colors.match("red.png", List.of(GREEN)).available());
        assertEquals(0, colors.match("dot.png", List.of(GREEN)).score());
        verify(thumbnails, never()).getOrCreate(anyString(), any());
        verify(thumbnails, never()).enqueue(anyString(), any());
    }

    @Test
    void transparencyDoesNotTurnEmptyCanvasIntoBlackOrDiluteGreenArt() throws Exception {
        BufferedImage transparent = new BufferedImage(640, 400, BufferedImage.TYPE_INT_ARGB);
        fill(transparent, 160, 100, 320, 200, new Color(80, 180, 90));
        thumbnail("transparent.png", transparent);
        assertTrue(colors.match("transparent.png", List.of(GREEN)).score() > .9);
        assertEquals(0, colors.match("transparent.png", List.of(BLACK)).score());
    }

    @Test
    void requiresAllRequestedColorsAndRanksSubstantialCoverageHigher() throws Exception {
        BufferedImage mix = canvas(640, 400, Color.GREEN);
        fill(mix, 320, 0, 320, 400, Color.RED);
        thumbnail("mix.png", mix);
        thumbnail("green.png", canvas(640, 400, Color.GREEN));
        assertTrue(colors.match("mix.png", List.of(RED, GREEN)).score() > .4);
        assertEquals(0, colors.match("green.png", List.of(RED, GREEN)).score());
        assertTrue(colors.match("green.png", List.of(GREEN)).score()
                > colors.match("mix.png", List.of(GREEN)).score());
    }

    @Test
    void cacheInvalidatesWhenThumbnailChangesWithoutGeneratingAnyFiles() throws Exception {
        Path path = thumbnail("updated.png", canvas(100, 100, Color.GREEN));
        assertTrue(colors.match("updated.png", List.of(GREEN)).score() > .9);
        ImageIO.write(canvas(100, 100, Color.RED), "png", path.toFile());
        Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() + 2000));
        assertEquals(0, colors.match("updated.png", List.of(GREEN)).score());
        assertTrue(colors.match("updated.png", List.of(RED)).score() > .9);
        try (var entries = Files.list(cacheRoot)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void missingMalformedOversizedAndEscapedThumbnailsDoNotMatchOrGenerate() throws Exception {
        when(thumbnails.cached(eq("missing.ai"), any())).thenReturn(Optional.empty());
        Path broken = Files.writeString(cacheRoot.resolve("broken.png"), "not an image");
        when(thumbnails.cached(eq("broken.ai"), any())).thenReturn(Optional.of(broken));
        Path large = Files.write(cacheRoot.resolve("large.png"), new byte[4 * 1024 * 1024 + 1]);
        when(thumbnails.cached(eq("large.ai"), any())).thenReturn(Optional.of(large));
        Path escaped = Files.writeString(uploadRoot.resolve("not-a-thumbnail.png"), "not an image");
        when(thumbnails.cached(eq("escaped.ai"), any())).thenReturn(Optional.of(escaped));
        thumbnail("wide.png", canvas(4097, 1, Color.GREEN));
        for (String name : List.of("missing.ai", "broken.ai", "large.ai", "escaped.ai", "wide.png")) {
            assertEquals(0, colors.match(name, List.of(GREEN)).score(), name);
            assertFalse(colors.match(name, List.of(GREEN)).available(), name);
        }
        verify(thumbnails, never()).getOrCreate(anyString(), any());
        verify(thumbnails, never()).enqueue(anyString(), any());
    }

    private Path thumbnail(String name, BufferedImage image) throws Exception {
        Path path = cacheRoot.resolve(name + ".png");
        ImageIO.write(image, "png", path.toFile());
        when(thumbnails.cached(eq(name), any())).thenReturn(Optional.of(path));
        return path;
    }

    private BufferedImage canvas(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        fill(image, 0, 0, width, height, color);
        return image;
    }

    private void fill(BufferedImage image, int x, int y, int width, int height, Color color) {
        var graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(x, y, width, height);
        } finally {
            graphics.dispose();
        }
    }
}
