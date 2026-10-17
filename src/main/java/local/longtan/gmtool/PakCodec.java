package local.longtan.gmtool;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

final class PakCodec {
    private static final byte[] MAP1;
    private static final byte[] MAP2;
    private static final byte[] MAP3;
    private static final byte[] MAP4;
    private static final byte[] MAP5;

    static {
        try (InputStream input = PakCodec.class.getResourceAsStream("/pak/maps.json")) {
            if (input == null) {
                throw new IllegalStateException("缺少 /pak/maps.json");
            }
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> maps = (Map<String, Object>) Json.parse(json);
            MAP1 = toBytes(maps.get("Map1"));
            MAP2 = toBytes(maps.get("Map2"));
            MAP3 = toBytes(maps.get("Map3"));
            MAP4 = toBytes(maps.get("Map4"));
            MAP5 = toBytes(maps.get("Map5"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private PakCodec() {
    }

    static byte[] decode(byte[] src, int index) {
        return coder(src, index, false);
    }

    private static byte[] coder(byte[] src, int index, boolean encode) {
        int n = src.length - index;
        byte[] out = new byte[n];
        if (n >= 8) {
            byte[] blk = new byte[8];
            int length = n / 8;
            int num = 0;
            while (length > 0) {
                System.arraycopy(src, num + index, blk, 0, 8);
                byte[] r = encode ? sub403160(blk) : sub403220(blk);
                System.arraycopy(r, 0, out, num, 8);
                num += 8;
                length--;
            }
        }
        int rem = n % 8;
        if (rem > 0) {
            int n1 = n - rem;
            System.arraycopy(src, n1 + index, out, n1, rem);
        }
        return out;
    }

    private static byte[] sub4032E0(byte[] a1, byte[] a2) {
        byte[] numArray = new byte[8];
        int num = 0;
        int num1 = 0;
        while (num1 < 16) {
            int num2 = a1[num] & 0xFF;
            int num3 = num2 >> 4;
            int num4 = num2 % 16;
            for (int i = 0; i < 8; i++) {
                int num5 = num1 * 128 + i;
                numArray[i] |= (byte) (a2[num5 + num3 * 8] | a2[num5 + (16 + num4) * 8]);
            }
            num1 += 2;
            num++;
        }
        return numArray;
    }

    private static byte[] sub403340(int a1, byte[] a2) {
        byte[] numArray = new byte[] { a2[4], a2[5], a2[6], a2[7] };
        byte[] numArray1 = sub4033B0(numArray, a1);
        return new byte[] {
                a2[4], a2[5], a2[6], a2[7],
                (byte) (numArray1[0] ^ a2[0]),
                (byte) (numArray1[1] ^ a2[1]),
                (byte) (numArray1[2] ^ a2[2]),
                (byte) (numArray1[3] ^ a2[3])
        };
    }

    private static byte[] sub403450(byte[] a1) {
        int b0 = a1[0] & 0xFF, b1 = a1[1] & 0xFF, b2 = a1[2] & 0xFF, b3 = a1[3] & 0xFF;
        return new byte[] {
                (byte) ((b3 << 7 | (b0 & 249 | b0 >> 2 & 6) >> 1) & 0xFF),
                (byte) (((b0 & 1 | b0 << 2) << 3 | (b1 >> 2 | b1 & 135) >> 3) & 0xFF),
                (byte) ((b2 >> 7 | (b1 & 31 | (b1 & 248) << 2) << 1) & 0xFF),
                (byte) ((b1 << 7 | (b2 & 249 | b2 >> 2 & 6) >> 1) & 0xFF),
                (byte) (((b2 & 1 | b2 << 2) << 3 | (b3 >> 2 | b3 & 135) >> 3) & 0xFF),
                (byte) ((b0 >> 7 | (b3 & 31 | (b3 & 248) << 2) << 1) & 0xFF)
        };
    }

    private static byte[] sub403520(byte[] a1) {
        return new byte[] {
                MAP4[(a1[0] & 0xFF) * 16 | ((a1[1] & 0xFF) >> 4)],
                MAP4[4096 + ((a1[2] & 0xFF) | ((a1[1] & 0xFF) % 16) * 256)],
                MAP4[8192 + ((a1[3] & 0xFF) * 16 | ((a1[4] & 0xFF) >> 4))],
                MAP4[12288 + ((a1[5] & 0xFF) | ((a1[4] & 0xFF) % 16) * 256)]
        };
    }

    private static byte[] sub4035A0(byte[] a1) {
        byte[] map3 = new byte[4];
        for (int i = 0; i < 4; i++) {
            int num = (i * 256 + (a1[i] & 0xFF)) * 4;
            map3[0] |= MAP3[num];
            map3[1] |= MAP3[num + 1];
            map3[2] |= MAP3[num + 2];
            map3[3] |= MAP3[num + 3];
        }
        return map3;
    }

    private static byte[] sub4033B0(byte[] a1, int a2) {
        byte[] numArray = sub403450(a1);
        int num = a2 * 6;
        byte[] map5 = new byte[] {
                (byte) (numArray[0] ^ MAP5[num]),
                (byte) (numArray[1] ^ MAP5[num + 1]),
                (byte) (numArray[2] ^ MAP5[num + 2]),
                (byte) (numArray[3] ^ MAP5[num + 3]),
                (byte) (numArray[4] ^ MAP5[num + 4]),
                (byte) (numArray[5] ^ MAP5[num + 5])
        };
        return sub4035A0(sub403520(map5));
    }

    private static byte[] sub403220(byte[] src) {
        byte[][] numArray = new byte[17][];
        numArray[0] = sub4032E0(src, MAP1);
        int num = 0;
        int num1 = 15;
        while (num1 >= 0) {
            numArray[num + 1] = sub403340(num1, numArray[num]);
            num1--;
            num++;
        }
        byte[] t = numArray[16];
        byte[] swapped = new byte[] { t[4], t[5], t[6], t[7], t[0], t[1], t[2], t[3] };
        return sub4032E0(swapped, MAP2);
    }

    private static byte[] sub403160(byte[] src) {
        byte[][] numArray = new byte[17][];
        numArray[0] = sub4032E0(src, MAP1);
        int num = 0;
        int num1 = 0;
        while (num1 <= 15) {
            numArray[num + 1] = sub403340(num1, numArray[num]);
            num1++;
            num++;
        }
        byte[] t = numArray[16];
        byte[] swapped = new byte[] { t[4], t[5], t[6], t[7], t[0], t[1], t[2], t[3] };
        return sub4032E0(swapped, MAP2);
    }

    @SuppressWarnings("unchecked")
    private static byte[] toBytes(Object value) {
        List<Object> list = (List<Object>) value;
        byte[] out = new byte[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = (byte) ((Number) list.get(i)).intValue();
        }
        return out;
    }
}
