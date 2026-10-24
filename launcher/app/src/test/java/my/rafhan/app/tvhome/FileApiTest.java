package my.rafhan.app.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The path, name and Range logic behind the Files tab, without a TV. */
public class FileApiTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void cleanNameKeepsOnlyTheLastSegment() {
        assertEquals("passwd", FileApi.cleanName("  ../../etc/passwd "));
        assertEquals("file.mp4", FileApi.cleanName("C:\\Users\\me\\file.mp4"));
        assertEquals("photo.jpg", FileApi.cleanName("/sdcard/DCIM/photo.jpg"));
        assertEquals("", FileApi.cleanName(null));
        assertEquals("", FileApi.cleanName("   "));
    }

    @Test
    public void cleanNameDropsLeadingDotsAndControlCharacters() {
        assertEquals("hidden.txt", FileApi.cleanName("..hidden.txt"));
        assertEquals("ab.txt", FileApi.cleanName("a\u0000b\u001f.txt"));
        assertEquals("movie.mkv", FileApi.cleanName("...movie.mkv"));
    }

    @Test
    public void uniqueAddsANumberBeforeTheExtension() throws Exception {
        File dir = tmp.newFolder("files");
        assertEquals("photo.jpg", FileApi.unique(dir, "photo.jpg").getName());

        assertTrue(new File(dir, "photo.jpg").createNewFile());
        assertEquals("photo (1).jpg", FileApi.unique(dir, "photo.jpg").getName());

        assertTrue(new File(dir, "photo (1).jpg").createNewFile());
        assertEquals("photo (2).jpg", FileApi.unique(dir, "photo.jpg").getName());
    }

    @Test
    public void uniqueHandlesNamesWithoutAnExtension() throws Exception {
        File dir = tmp.newFolder("files");
        assertTrue(new File(dir, "notes").createNewFile());
        assertEquals("notes (1)", FileApi.unique(dir, "notes").getName());
    }

    @Test
    public void parseRangeUnderstandsTheThreeShapes() {
        assertArrayEquals(new long[] {0, 9}, FileApi.parseRange("bytes=0-9", 40));
        assertArrayEquals(new long[] {30, 39}, FileApi.parseRange("bytes=30-", 40));
        assertArrayEquals(new long[] {36, 39}, FileApi.parseRange("bytes=-4", 40));
        assertArrayEquals(new long[] {5, 39}, FileApi.parseRange("bytes=5-100", 40)); // clamped
    }

    @Test
    public void parseRangeRefusesWhatItCannotServe() {
        assertNull(FileApi.parseRange(null, 40));
        assertNull(FileApi.parseRange("bytes=0-9", 0));            // empty file
        assertNull(FileApi.parseRange("bytes=0-1,3-4", 40));       // multi-range
        assertNull(FileApi.parseRange("items=0-1", 40));           // not bytes
        assertNull(FileApi.parseRange("bytes=50-60", 40));         // starts past the end
        assertNull(FileApi.parseRange("bytes=abc-def", 40));
    }

    @Test
    public void humanReadsLikeAFileSize() {
        assertEquals("0 KB", FileApi.human(500));
        assertEquals("1 KB", FileApi.human(1536));
        assertEquals("2 MB", FileApi.human(2L << 20));
        assertEquals("1.5 GB", FileApi.human((long) (1.5 * (1L << 30))));
    }

    @Test
    public void resolveKeepsPathsInsideARoot() throws Exception {
        File root = FileApi.canonical(tmp.newFolder("root"));
        File sub = new File(root, "DCIM");
        assertTrue(sub.mkdirs());
        File photo = new File(sub, "photo.jpg");
        assertTrue(photo.createNewFile());

        List<File> roots = Collections.singletonList(root);
        assertEquals(photo.getCanonicalFile(), FileApi.resolve(photo.getPath(), roots));
        assertEquals(root, FileApi.resolve(root.getPath(), roots));   // the root itself is allowed
        assertNull(FileApi.resolve("/etc/passwd", roots));
        assertNull(FileApi.resolve(root + "/../elsewhere.jpg", roots)); // ".." escapes the root
        assertNull(FileApi.resolve("", roots));
    }

    @Test
    public void resolveChecksEveryRoot() throws Exception {
        File a = tmp.newFolder("a"), b = tmp.newFolder("b");
        File inB = new File(b, "clip.mp4");
        assertTrue(inB.createNewFile());
        List<File> roots = Arrays.asList(FileApi.canonical(a), FileApi.canonical(b));
        assertNotNull(FileApi.resolve(inB.getPath(), roots));
    }

    @Test
    public void insideIsPrefixSafe() throws Exception {
        File root = tmp.newFolder("root");
        File child = new File(root, "x.txt");
        File sibling = new File(root.getParentFile(), root.getName() + "-evil/x.txt");
        assertTrue(FileApi.inside(child, root));
        assertTrue(FileApi.inside(root, root));
        assertFalse(FileApi.inside(sibling, root));
    }
}
