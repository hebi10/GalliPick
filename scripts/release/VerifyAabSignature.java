import java.io.File;
import java.io.InputStream;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** JDK 17 source-file verifier: no keystore, password, or external dependency. */
public class VerifyAabSignature {
    private static boolean signingMetadata(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) return false;
        String leaf = upper.substring("META-INF/".length());
        return !leaf.contains("/") && (leaf.equals("MANIFEST.MF")
            || leaf.matches("[^/]+\\.(SF|RSA|DSA|EC)") || leaf.startsWith("SIG-"));
    }

    public static void main(String[] args) {
        try {
            if (args.length != 2 || !args[1].matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException();
            }
            int verifiedEntries = 0;
            Set<String> names = new HashSet<>();
            try (JarFile jar = new JarFile(new File(args[0]), true)) {
                if (jar.getManifest() == null) throw new SecurityException();
                var entries = jar.entries();
                byte[] buffer = new byte[65536];
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!names.add(entry.getName().toUpperCase(Locale.ROOT))) throw new SecurityException();
                    if (entry.isDirectory()) continue;
                    // Signers are populated only after reading this entry to EOF.
                    try (InputStream input = jar.getInputStream(entry)) {
                        while (input.read(buffer) != -1) { }
                    }
                    if (signingMetadata(entry.getName())) continue;
                    CodeSigner[] signers = entry.getCodeSigners();
                    if (signers == null || signers.length != 1) throw new SecurityException();
                    var certificate = signers[0].getSignerCertPath().getCertificates().get(0);
                    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
                    if (!digest.equals(args[1])) throw new SecurityException();
                    verifiedEntries++;
                }
            }
            if (verifiedEntries == 0) throw new SecurityException();
            System.out.println("{\"certificate_sha256\":\"" + args[1] + "\",\"verified_entries\":" + verifiedEntries + "}");
        } catch (Exception failure) {
            // Never expose archive contents, local paths, certificate subjects, or tool diagnostics.
            System.err.println("AAB payload signature verification failed.");
            System.exit(1);
        }
    }
}
