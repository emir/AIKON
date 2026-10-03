import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.Inflater;

import ru.eustas.zopfli.Options;
import ru.eustas.zopfli.Zopfli;

/**
 * Build tool (host only, never packaged): raw deflate data for the JAR
 * entries, made with Zopfli (CafeUndZopfli, deps.lock), which packs about
 * 5% tighter than zlib level 9. tools/package.py writes the JAR from it.
 *
 *   java -cp ZOPFLI_CLASSES:. ZopfliDir OUT_DIR IN_DIR...
 *
 * Every file under each IN_DIR is written to OUT_DIR/<same path>; each is
 * inflated again and compared, so a wrong output stops the build. Each
 * file gets its own Zopfli (its random step starts from the same seed), so
 * a file's bytes depend only on its content: same inputs, same bytes.
 */
public class ZopfliDir {

    static final int ITERATIONS = 15;

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        for (int i = 1; i < args.length; i++) {
            File in = new File(args[i]);
            walk(in, in, out);
        }
    }

    static void walk(File root, File f, File out) throws Exception {
        if (f.isDirectory()) {
            String[] names = f.list();
            Arrays.sort(names);
            for (int i = 0; i < names.length; i++) {
                walk(root, new File(f, names[i]), out);
            }
            return;
        }
        String rel = root.toPath().relativize(f.toPath()).toString();
        byte[] data = Files.readAllBytes(f.toPath());
        byte[] packed = deflate(data);
        File dst = new File(out, rel);
        dst.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(dst);
        o.write(packed);
        o.close();
    }

    static byte[] deflate(byte[] data) throws Exception {
        int block = 1 << 16;
        while (block < data.length) {
            block <<= 1;
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        new Zopfli(block).compress(new Options(Options.OutputFormat.DEFLATE,
                Options.BlockSplitting.FIRST, ITERATIONS), data, o);
        byte[] packed = o.toByteArray();
        Inflater inf = new Inflater(true);
        inf.setInput(packed);
        byte[] back = new byte[data.length + 1];
        int n = inf.inflate(back);
        if (n != data.length || !inf.finished()
                || !Arrays.equals(Arrays.copyOf(back, n), data)) {
            throw new IOException("Zopfli output does not inflate back to its input");
        }
        inf.end();
        return packed;
    }
}
