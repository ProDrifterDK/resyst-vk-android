// r13e n-gram builder (desktop only; JDK 21, no dependencies): `java -Xmx3g tools/ngram-build/NgramBuild.java ...`
//
// Counts word unigrams, bigrams and trigrams of tokenised Spanish sentences, keeps Witten-Bell continuation-type counts
// (computed BEFORE pruning), prunes by count and writes the VKNG v1 asset read by core/ContextNgram.kt.
//
//   java -Xmx3g NgramBuild.java --out es.vkng --report report.json --vocab 120000 --k2 3 --k3 3 --shards 8
//        --tmp DIR --cases app/src/test/resources/r13e/tokenizer-cases.tsv  lines:1:wiki.txt  tatoeba:8:spa.tsv
//
// Corpus specs: FORMAT:WEIGHT:PATH. FORMAT = lines (one paragraph per line) | tatoeba (id TAB lang TAB text). WEIGHT =
// integer multiplier of that corpus' counts. The tokenizer below must stay identical to ContextTokens.kt: both are
// checked against tokenizer-cases.tsv (this tool refuses to run when a case fails).
//
// VKNG v1 (big-endian, java.io.DataOutputStream):
//   "VKNG" int version=2, int V, long N (tokens counted, <s> excluded), long unkTypes (distinct OOV word types),
//   int n2, int n3, int k2, int k3
//   V x writeUTF(word)                      ids: 0 <unk>, 1 <s>, 2 </s>, 3 ",", 4 <n>, then words by count desc
//   V x (varint count, varint T1, varint kept1)   T1 = distinct words following it (unpruned); kept1 = Σ counts of its
//                                                 KEPT bigrams (count − kept1 = pruned mass, handed to the backoff)
//   n2 x (varint keyDelta, byte q(count), byte q(T2), byte f2)   key = a<<20 | b, ascending; T2 = distinct words after
//                                                 (a,b); f2 = round(255 · pruned trigram mass after (a,b) / count)
//   n3 x (varint keyDelta, byte q(count))                   key = a<<40 | b<<20 | c, ascending
//   q(c) = 0 if c == 0 else min(255, 1 + round(8 log2 c)); counts are weighted.
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.Deflater;

public class NgramBuild {
    // ── tokenizer (keep identical to ContextTokens.kt) ─────────────────────────────────────────────────────────
    static final Set<String> ABBR = Set.of("sr", "sra", "srta", "sres", "dr", "dra", "drs", "lic", "ing", "prof", "arq",
        "abog", "ud", "uds", "vd", "vds", "pág", "pag", "págs", "núm", "num", "nro", "art", "cap", "vol", "fig", "ej",
        "aprox", "av", "avda", "depto", "dpto", "tel", "cel", "cía", "ltda", "admin", "máx", "mín", "mr", "mrs", "ms", "st",
        "vs", "approx", "dept", "jr");
    static final String MARKS = ".?!…";
    static final String CLOSERS = "\"')]}»”’";
    static final String COMMAS = ",;:()[]—–";

    static List<List<String>> sentences(String text) {
        String t = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        List<List<String>> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int n = t.length(), i = 0, lastWordEnd = -1;
        String lastWord = null;
        while (i < n) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                StringBuilder sb = new StringBuilder();
                boolean digit = false;
                while (i < n) {
                    char d = t.charAt(i);
                    if (Character.isLetter(d)) { sb.append(d); i++; }
                    else if (Character.isDigit(d)) { digit = true; sb.append(d); i++; }
                    else if ((d == '\'' || d == '’') && i + 1 < n && Character.isLetter(t.charAt(i + 1)) && sb.length() > 0
                        && Character.isLetter(sb.charAt(sb.length() - 1))) { sb.append('\''); i++; }
                    else if ((d == '.' || d == ',' || d == ':') && digit && i + 1 < n && Character.isDigit(t.charAt(i + 1))
                        && Character.isDigit(sb.charAt(sb.length() - 1))) { sb.append(d); i++; }
                    else break;
                }
                String w = digit ? "<n>" : sb.toString();
                cur.add(w);
                lastWord = w;
                lastWordEnd = i;
                continue;
            }
            if (c == '\n') { end(out, cur); cur = new ArrayList<>(); i++; continue; }
            if (MARKS.indexOf(c) >= 0) {
                int j = i;
                while (j < n && MARKS.indexOf(t.charAt(j)) >= 0) j++;
                int k = j;
                while (k < n && CLOSERS.indexOf(t.charAt(k)) >= 0) k++;
                boolean boundary = k >= n || Character.isWhitespace(t.charAt(k));
                if (boundary && j == i + 1 && c == '.' && lastWordEnd == i && lastWord != null
                    && ((lastWord.length() == 1 && Character.isLetter(lastWord.charAt(0))) || ABBR.contains(lastWord))) boundary = false;
                if (boundary) { end(out, cur); cur = new ArrayList<>(); i = k; } else i = j;
                continue;
            }
            if (COMMAS.indexOf(c) >= 0) { comma(cur); i++; continue; }
            if (c == '-') {
                boolean inWord = i > 0 && i + 1 < n && Character.isLetter(t.charAt(i - 1)) && Character.isLetter(t.charAt(i + 1));
                if (!inWord) comma(cur);
                i++;
                continue;
            }
            i++;
        }
        end(out, cur);
        return out;
    }

    static void comma(List<String> cur) {
        if (!cur.isEmpty() && !cur.get(cur.size() - 1).equals(",")) cur.add(",");
    }

    static void end(List<List<String>> out, List<String> cur) {
        while (!cur.isEmpty() && cur.get(cur.size() - 1).equals(",")) cur.remove(cur.size() - 1);
        boolean any = false;
        for (String s : cur) if (!s.equals(",")) { any = true; break; }
        if (any) out.add(cur);
    }

    static void checkCases(Path p) throws IOException {
        int n = 0;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            String in = f[0].replace("\\n", "\n");
            StringBuilder got = new StringBuilder();
            for (List<String> s : sentences(in)) { if (got.length() > 0) got.append(" | "); got.append(String.join(" ", s)); }
            if (!got.toString().equals(f[1])) throw new IllegalStateException("tokenizer case failed: «" + f[0] + "» got «" + got + "» want «" + f[1] + "»");
            n++;
        }
        System.err.println("tokenizer cases ok: " + n);
    }

    // ── corpora ───────────────────────────────────────────────────────────────────────────────────────────────
    record Corpus(String fmt, int weight, Path path) {}

    interface SentenceSink { void accept(List<String> s) throws IOException; }

    static void each(Corpus c, SentenceSink sink) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(c.path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String text = line;
                if (c.fmt.equals("tatoeba")) {
                    String[] f = line.split("\t", 3);
                    if (f.length < 3 || !f[1].equals("spa")) continue;
                    text = f[2];
                }
                for (List<String> s : sentences(text)) sink.accept(s);
            }
        }
    }

    // ── a minimal long -> int open-addressing map ─────────────────────────────────────────────────────────────
    static final class LongIntMap {
        long[] keys; int[] vals; int size, mask;
        LongIntMap(int cap) { int c = Integer.highestOneBit(Math.max(16, cap) - 1) << 1; keys = new long[c]; vals = new int[c]; mask = c - 1; }
        static int mix(long k) { k ^= k >>> 33; k *= 0xff51afd7ed558ccdL; k ^= k >>> 33; k *= 0xc4ceb9fe1a85ec53L; k ^= k >>> 33; return (int) k; }
        void add(long key, int v) {
            long k = key + 1;
            int i = mix(k) & mask;
            while (true) {
                if (keys[i] == 0) { keys[i] = k; vals[i] = v; if (++size * 10 > keys.length * 7) grow(); return; }
                if (keys[i] == k) { long s = (long) vals[i] + v; vals[i] = (int) Math.min(Integer.MAX_VALUE, s); return; }
                i = (i + 1) & mask;
            }
        }
        void grow() {
            long[] ok = keys; int[] ov = vals;
            keys = new long[ok.length * 2]; vals = new int[ok.length * 2]; mask = keys.length - 1; size = 0;
            for (int i = 0; i < ok.length; i++) if (ok[i] != 0) add(ok[i] - 1, ov[i]);
        }
    }

    static int q(long c) { return c <= 0 ? 0 : (int) Math.min(255, 1 + Math.round(8 * Math.log(c) / Math.log(2))); }

    static void varint(DataOutputStream o, long v) throws IOException {
        while ((v & ~0x7FL) != 0) { o.writeByte((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        o.writeByte((int) v);
    }

    // ── main ──────────────────────────────────────────────────────────────────────────────────────────────────
    public static void main(String[] a) throws Exception {
        Map<String, String> opt = new HashMap<>();
        List<Corpus> corpora = new ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            if (a[i].startsWith("--")) opt.put(a[i].substring(2), a[++i]);
            else { String[] f = a[i].split(":", 3); corpora.add(new Corpus(f[0], Integer.parseInt(f[1]), Paths.get(f[2]))); }
        }
        checkCases(Paths.get(opt.get("cases")));
        int vMax = Integer.parseInt(opt.getOrDefault("vocab", "120000"));
        int k2 = Integer.parseInt(opt.getOrDefault("k2", "3"));
        int k3 = Integer.parseInt(opt.getOrDefault("k3", "3"));
        int shards = Integer.parseInt(opt.getOrDefault("shards", "8"));
        Path tmp = Paths.get(opt.get("tmp"));
        Files.createDirectories(tmp);
        Map<String, Object> rep = new LinkedHashMap<>();
        long t0 = System.currentTimeMillis();

        // pass A: word counts (weighted)
        HashMap<String, long[]> wc = new HashMap<>();
        List<Map<String, Object>> crep = new ArrayList<>();
        for (Corpus c : corpora) {
            long[] st = new long[2];
            each(c, s -> { st[0]++; for (String w : s) { st[1]++; if (!w.equals(",") && !w.equals("<n>")) wc.computeIfAbsent(w, k -> new long[1])[0] += c.weight; } });
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", c.path.getFileName().toString()); m.put("format", c.fmt); m.put("weight", c.weight);
            m.put("sentences", st[0]); m.put("tokens", st[1]); m.put("sha256", sha256(c.path));
            crep.add(m);
            System.err.println("pass A " + m);
        }
        rep.put("corpora", crep);
        List<String> words = new ArrayList<>(wc.keySet());
        words.sort((x, y) -> { int d = Long.compare(wc.get(y)[0], wc.get(x)[0]); return d != 0 ? d : x.compareTo(y); });
        String[] specials = {"<unk>", "<s>", "</s>", ",", "<n>"};
        int V = Math.min(vMax, words.size() + specials.length);
        String[] vocab = new String[V];
        HashMap<String, Integer> id = new HashMap<>();
        for (int i = 0; i < specials.length; i++) { vocab[i] = specials[i]; id.put(specials[i], i); }
        for (int i = specials.length; i < V; i++) { vocab[i] = words.get(i - specials.length); id.put(vocab[i], i); }
        long unkTypes = Math.max(1, words.size() - (V - specials.length));
        long minVocabCount = V > specials.length ? wc.get(vocab[V - 1])[0] : 0;
        rep.put("word_types", words.size()); rep.put("V", V); rep.put("unk_types", unkTypes); rep.put("vocab_min_weighted_count", minVocabCount);

        // pass B: id streams (one file per corpus)
        List<Path> streams = new ArrayList<>();
        for (int ci = 0; ci < corpora.size(); ci++) {
            Path sp = tmp.resolve("stream" + ci + ".bin");
            streams.add(sp);
            try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(sp), 1 << 20))) {
                each(corpora.get(ci), s -> {
                    o.writeInt(1);
                    for (String w : s) o.writeInt(id.getOrDefault(w, 0));
                    o.writeInt(2);
                });
            }
        }
        System.err.println("pass B done " + (System.currentTimeMillis() - t0) / 1000 + "s");

        // pass C: unigrams + bigrams
        long[] uni = new long[V];
        LongIntMap bi = new LongIntMap(1 << 24);
        for (int ci = 0; ci < corpora.size(); ci++) {
            int w = corpora.get(ci).weight;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(streams.get(ci)), 1 << 20))) {
                int prev = -1;
                while (true) {
                    int x;
                    try { x = in.readInt(); } catch (EOFException e) { break; }
                    uni[x] += w;
                    if (x != 1 && prev >= 0) bi.add(((long) prev << 20) | x, w);
                    prev = (x == 2) ? -1 : x;
                }
            }
        }
        long N = 0;
        for (int i = 0; i < V; i++) if (i != 1) N += uni[i];
        long[] t1 = new long[V];
        long[] kept1 = new long[V];
        int keptBi = 0;
        for (int i = 0; i < bi.keys.length; i++) if (bi.keys[i] != 0) {
            int a0 = (int) ((bi.keys[i] - 1) >>> 20);
            t1[a0]++;
            if (bi.vals[i] >= k2) { keptBi++; kept1[a0] += bi.vals[i]; }
        }
        long[] bKey = new long[keptBi];
        int[] bCnt = new int[keptBi];
        {
            long[] tmpK = new long[keptBi];
            int j = 0;
            for (int i = 0; i < bi.keys.length; i++) if (bi.keys[i] != 0 && bi.vals[i] >= k2) tmpK[j++] = bi.keys[i] - 1;
            Arrays.sort(tmpK);
            for (int i = 0; i < bi.keys.length; i++) if (bi.keys[i] != 0 && bi.vals[i] >= k2) {
                int p = Arrays.binarySearch(tmpK, bi.keys[i] - 1);
                bCnt[p] = bi.vals[i];
            }
            bKey = tmpK;
        }
        rep.put("N", N); rep.put("bigrams_distinct", bi.size); rep.put("bigrams_kept", keptBi);
        bi = null;
        System.err.println("pass C done " + (System.currentTimeMillis() - t0) / 1000 + "s bigrams " + keptBi);

        // pass D: trigrams, sharded by their context (a,b) so each context's continuation types are complete
        int[] t2 = new int[keptBi];
        long[] kept2 = new long[keptBi];
        long[] triK = new long[1 << 20];
        int[] triC = new int[1 << 20];
        int nTri = 0;
        long triDistinct = 0;
        for (int sh = 0; sh < shards; sh++) {
            LongIntMap tri = new LongIntMap(1 << 23);
            for (int ci = 0; ci < corpora.size(); ci++) {
                int w = corpora.get(ci).weight;
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(streams.get(ci)), 1 << 20))) {
                    int p2 = -1, p1 = -1;
                    while (true) {
                        int x;
                        try { x = in.readInt(); } catch (EOFException e) { break; }
                        if (x == 1) { p2 = -1; p1 = 1; continue; }
                        if (p2 >= 0) {
                            long ctx = ((long) p2 << 20) | p1;
                            if (Math.floorMod(LongIntMap.mix(ctx + 1), shards) == sh) tri.add((ctx << 20) | x, w);
                        }
                        p2 = p1; p1 = x;
                        if (x == 2) { p2 = -1; p1 = -1; }
                    }
                }
            }
            triDistinct += tri.size;
            for (int i = 0; i < tri.keys.length; i++) {
                if (tri.keys[i] == 0) continue;
                long k = tri.keys[i] - 1;
                int p = Arrays.binarySearch(bKey, k >>> 20);
                if (p >= 0) t2[p]++;
                if (tri.vals[i] >= k3 && p >= 0) {
                    kept2[p] += tri.vals[i];
                    if (nTri == triK.length) { triK = Arrays.copyOf(triK, nTri * 2); triC = Arrays.copyOf(triC, nTri * 2); }
                    triK[nTri] = k; triC[nTri] = tri.vals[i]; nTri++;
                }
            }
            System.err.println("pass D shard " + sh + " distinct " + tri.size + " kept so far " + nTri + " " + (System.currentTimeMillis() - t0) / 1000 + "s");
        }
        Integer[] order = new Integer[nTri];
        for (int i = 0; i < nTri; i++) order[i] = i;
        final long[] tk = triK;
        Arrays.sort(order, (x, y) -> Long.compare(tk[x], tk[y]));
        rep.put("trigrams_distinct", triDistinct); rep.put("trigrams_kept", nTri);

        // write
        Path outP = Paths.get(opt.get("out"));
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(outP), 1 << 20))) {
            o.writeBytes("VKNG"); o.writeInt(2); o.writeInt(V); o.writeLong(N); o.writeLong(unkTypes);
            o.writeInt(keptBi); o.writeInt(nTri); o.writeInt(k2); o.writeInt(k3);
            for (String w : vocab) o.writeUTF(w);
            for (int i = 0; i < V; i++) { varint(o, uni[i]); varint(o, t1[i]); varint(o, kept1[i]); }
            long prev = 0;
            for (int i = 0; i < keptBi; i++) {
                varint(o, bKey[i] - prev); prev = bKey[i]; o.writeByte(q(bCnt[i])); o.writeByte(q(t2[i]));
                long pruned = Math.max(0, (long) bCnt[i] - kept2[i]);
                o.writeByte((int) Math.min(255, Math.round(255.0 * pruned / Math.max(1, bCnt[i]))));
            }
            prev = 0;
            for (int i = 0; i < nTri; i++) { long k = triK[order[i]]; varint(o, k - prev); prev = k; o.writeByte(q(triC[order[i]])); }
        }
        byte[] all = Files.readAllBytes(outP);
        Deflater d = new Deflater(9);
        d.setInput(all); d.finish();
        byte[] buf = new byte[1 << 20];
        long z = 0;
        while (!d.finished()) z += d.deflate(buf);
        rep.put("file_bytes", all.length); rep.put("deflate9_bytes", z); rep.put("sha256", sha256(outP));
        rep.put("k2", k2); rep.put("k3", k3); rep.put("seconds", (System.currentTimeMillis() - t0) / 1000);
        for (Path sp : streams) Files.deleteIfExists(sp);
        String js = json(rep);
        if (opt.containsKey("report")) Files.writeString(Paths.get(opt.get("report")), js + "\n");
        System.out.println(js);
    }

    static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(Files.newInputStream(p), 1 << 20)) {
            byte[] b = new byte[1 << 20];
            int r;
            while ((r = in.read(b)) > 0) md.update(b, 0, r);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static String json(Object x) {
        if (x instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            for (Map.Entry<?, ?> e : m.entrySet()) { if (sb.length() > 1) sb.append(", "); sb.append('"').append(e.getKey()).append("\": ").append(json(e.getValue())); }
            return sb.append('}').toString();
        }
        if (x instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (Object e : l) { if (sb.length() > 1) sb.append(", "); sb.append(json(e)); }
            return sb.append(']').toString();
        }
        if (x instanceof String s) return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        return String.valueOf(x);
    }
}
