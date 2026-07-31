package dev.bettervillagers.building;

/**
 * 经典改进柏林噪声（Improved Perlin Noise，Ken Perlin 2002）。
 * <p>
 * <b>核心约束</b>：本类完整保留原版柏林算法的全部核心逻辑（置换表、fade、grad、lerp），
 * 仅用于建筑场地分析与复杂度量化，<b>绝不参与</b> Minecraft 世界地形生成，
 * 不会修改/替换/破坏原版噪声生成器或世界种子逻辑。
 * <p>
 * 参考：Ken Perlin, "Improving Noise", SIGGRAPH 2002.
 */
final class PerlinNoise {

    private static final int FBM_OCTAVES = 4;
    private static final double FBM_PERSISTENCE = 0.5;
    private static final double FBM_LACUNARITY = 2.0;

    /** 标准置换表种子（经典实现固定表 + 种子打乱）。 */
    private final int[] p = new int[512];

    PerlinNoise(long seed) {
        int[] source = new int[256];
        for (int i = 0; i < 256; i++) {
            source[i] = i;
        }
        // 线性同余打乱（保持经典算法结构，仅置换顺序依赖 seed）
        long s = seed;
        for (int i = 255; i > 0; i--) {
            s = (s * 6364136223846793005L + 1442695040888963407L);
            int j = (int) ((s >>> 33) % (i + 1));
            int tmp = source[i];
            source[i] = source[j];
            source[j] = tmp;
        }
        for (int i = 0; i < 256; i++) {
            p[i] = source[i];
            p[256 + i] = source[i];
        }
    }

    /** 三维柏林噪声，返回约 [-1, 1]。 */
    private double noise(double x, double y, double z) {
        int X = floor(x) & 255;
        int Y = floor(y) & 255;
        int Z = floor(z) & 255;
        x -= floor(x);
        y -= floor(y);
        z -= floor(z);
        double u = fade(x);
        double v = fade(y);
        double w = fade(z);
        int A = p[X] + Y;
        int AA = p[A] + Z;
        int AB = p[A + 1] + Z;
        int B = p[X + 1] + Y;
        int BA = p[B] + Z;
        int BB = p[B + 1] + Z;
        return lerp(w,
                lerp(v,
                        lerp(u, grad(p[AA], x, y, z), grad(p[BA], x - 1, y, z)),
                        lerp(u, grad(p[AB], x, y - 1, z), grad(p[BB], x - 1, y - 1, z))),
                lerp(v,
                        lerp(u, grad(p[AA + 1], x, y, z - 1), grad(p[BA + 1], x - 1, y, z - 1)),
                        lerp(u, grad(p[AB + 1], x, y - 1, z - 1), grad(p[BB + 1], x - 1, y - 1, z - 1))));
    }

    /**
     * 分形布朗运动（fBm）：多层八度叠加，工程上用于粗糙度/起伏量化。
     * 不改变世界生成，仅作场地复杂度评估。
     */
    double fbm(double x, double y, double z) {
        double total = 0;
        double amplitude = 1;
        double frequency = 1;
        double maxValue = 0;
        for (int i = 0; i < FBM_OCTAVES; i++) {
            total += noise(x * frequency, y * frequency, z * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= FBM_PERSISTENCE;
            frequency *= FBM_LACUNARITY;
        }
        return maxValue == 0 ? 0 : total / maxValue;
    }

    // ---------- 经典 Perlin 核心（不可删改语义） ----------

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    private static int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }
}
