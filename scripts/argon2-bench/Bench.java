import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import java.util.Arrays;

/**
 * Argon2id's cost on the machine it runs on, through the encoder the app uses,
 * with the app's parameters (Argon2idPasswordHasher: salt 16, hash 32, p=1,
 * m=19456 KiB) and the iteration counts given as arguments. It times
 * `matches`, which is what a login pays.
 *
 * The number that matters is the production server's, so it runs there, in
 * the production image: same JVM, same libraries, same CPU. The image has no
 * shell and no compiler, so compile here (JDK 25) and copy the class over:
 *
 *   javac --release 25 -cp <spring-security-crypto jar> -d out scripts/argon2-bench/Bench.java
 *   scp -r out moyi:/root/argon2bench
 *   ssh moyi 'docker run --rm --memory 2g -v /root/argon2bench:/bench:ro \
 *     --entrypoint /layers/paketo-buildpacks_bellsoft-liberica/jre/bin/java <image> \
 *     -cp "/bench:/workspace/BOOT-INF/lib/*" Bench 4 5 6 7 8'
 *
 * Re-run it on a new server, a new instance type, or after a JDK or Bouncy
 * Castle upgrade, then set MOYI_SECURITY_ARGON2_ITERATIONS in
 * config/deploy.yml to the count nearest ~150 ms.
 */
public class Bench {
    public static void main(String[] args) {
        int runs = 15;
        String pw = "a-realistic-passphrase-of-some-length";
        System.out.printf("cpus=%d java=%s%n", Runtime.getRuntime().availableProcessors(), System.getProperty("java.version"));
        for (String a : args) {
            int t = Integer.parseInt(a);
            Argon2PasswordEncoder enc = new Argon2PasswordEncoder(16, 32, 1, 19456, t);
            for (int i = 0; i < 5; i++) enc.encode(pw); // warm-up: JIT and allocator
            String h = enc.encode(pw);
            long[] ms = new long[runs];
            for (int i = 0; i < runs; i++) {
                long s = System.nanoTime();
                if (!enc.matches(pw, h)) throw new IllegalStateException("no match");
                ms[i] = (System.nanoTime() - s) / 1_000;
            }
            Arrays.sort(ms);
            System.out.printf("t=%2d  median %6.1f ms  p05 %6.1f  p95 %6.1f  (n=%d)%n",
                t, ms[runs / 2] / 1000.0, ms[0] / 1000.0, ms[runs - 2] / 1000.0, runs);
        }
    }
}
