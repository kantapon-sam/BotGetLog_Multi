package com.java.updater;

import com.java.shared.AppMetadata;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Runs the release updater and relaunch in a disposable installation only. */
public final class ReleaseInstallRegression {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            Files.write(Paths.get("installed-version.txt"),
                    AppMetadata.getCurrentVersion().getBytes(StandardCharsets.UTF_8));
            return;
        }
        Path previous = Paths.get(args[0]).toAbsolutePath();
        Path release = Paths.get(args[1]).toAbsolutePath();
        String expected = args[2];
        Path sandbox = Files.createTempDirectory("bot-release-install-");
        Path target = Files.createDirectory(sandbox.resolve("installed"));
        try (Stream<Path> files = Files.walk(previous)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Path rel = previous.relativize(p);
                if (rel.startsWith("_output") || rel.startsWith("launcher-data")) continue;
                Path dest = target.resolve(rel);
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest);
            }
        }
        Path input = target.resolve("UserInterface_Input.xlsx");
        try (Workbook book = new XSSFWorkbook()) {
            book.createSheet("deviceList_TRUE").createRow(0).createCell(0).setCellValue("KEEP-INVENTORY");
            book.createSheet("Credentials").createRow(0).createCell(0).setCellValue("SYNTHETIC-CREDENTIAL-SENTINEL");
            book.createSheet("CustomData").createRow(0).createCell(0).setCellValue("KEEP-CUSTOM-DATA");
            book.createSheet("cmdSet").createRow(0).createCell(0).setCellValue("OLD-COMMANDS");
            try (OutputStream output = Files.newOutputStream(input)) { book.write(output); }
        }
        Path history = target.resolve("_output/Total_Log/keep.txt");
        Path prefs = target.resolve("launcher-data/preferences.properties");
        for (Path p : new Path[]{history, prefs}) {
            Files.createDirectories(p.getParent());
            Files.write(p, "KEEP-USER-FILE".getBytes(StandardCharsets.UTF_8));
        }
        Path probe = target.resolve("ReleaseVersionProbe.jar");
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.putValue("Manifest-Version", "1.0");
        attrs.putValue("Main-Class", ReleaseInstallRegression.class.getName());
        attrs.putValue("Class-Path", "BotGetLog_TrueCorp.jar");
        String resource = ReleaseInstallRegression.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(probe), manifest);
             InputStream bytes = ReleaseInstallRegression.class.getClassLoader().getResourceAsStream(resource)) {
            require(bytes != null, "missing probe class");
            output.putNextEntry(new JarEntry(resource));
            byte[] buffer = new byte[8192];
            int count;
            while ((count = bytes.read(buffer)) >= 0) output.write(buffer, 0, count);
            output.closeEntry();
        }
        Path zip = sandbox.resolve("update.zip");
        Files.copy(release, zip, StandardCopyOption.REPLACE_EXISTING);
        String javaCommand = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = new java.io.File(UpdaterMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath()
                + java.io.File.pathSeparator + previous.resolve("lib") + java.io.File.separator + "*";
        Path log = sandbox.resolve("update-process.log");
        Process process = new ProcessBuilder(javaCommand, "-Djava.awt.headless=true", "-cp", classpath,
                UpdaterMain.class.getName(), "--zip", zip.toString(), "--target", target.toString(),
                "--launch", probe.toString(), "--java", javaCommand)
                .directory(sandbox.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Updater timed out: " + log);
        }
        require(process.exitValue() == 0, "Updater failed: " + log);
        Path marker = target.resolve("installed-version.txt");
        long deadline = System.currentTimeMillis() + 15000;
        while (!Files.exists(marker) && System.currentTimeMillis() < deadline) Thread.sleep(100);
        require(Files.exists(marker), "Relaunched probe did not run");
        require(expected.equals(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8)), "Wrong relaunched version");
        for (String jar : new String[]{"BotGetLog_TrueCorp.jar", "Bot Tool Launcher.jar"}) {
            try (JarFile installed = new JarFile(target.resolve(jar).toFile())) {
                require(expected.equals(installed.getManifest().getMainAttributes().getValue("Implementation-Version")), jar);
            }
        }
        try (JarFile installed = new JarFile(target.resolve("BotGetLog_TrueCorp.jar").toFile())) {
            require(installed.getJarEntry("com/java/botgetlog/truecorp/NodeCollectionLease.class") != null, "Missing node lease fix");
        }
        try (Workbook book = WorkbookFactory.create(input.toFile())) {
            require("KEEP-INVENTORY".equals(book.getSheet("deviceList_TRUE").getRow(0).getCell(0).getStringCellValue()), "Inventory lost");
            require("SYNTHETIC-CREDENTIAL-SENTINEL".equals(book.getSheet("Credentials").getRow(0).getCell(0).getStringCellValue()), "Credentials lost");
            require("KEEP-CUSTOM-DATA".equals(book.getSheet("CustomData").getRow(0).getCell(0).getStringCellValue()), "Custom sheet lost");
            require(book.getSheet("cmdSet").getLastRowNum() > 0, "Command set not synchronized");
            org.apache.poi.ss.usermodel.Sheet commands = book.getSheet("cmdSet");
            int juniper = -1;
            for (org.apache.poi.ss.usermodel.Cell cell : commands.getRow(0))
                if ("J-LLDP-Link_OPTIC".equals(cell.toString())) juniper = cell.getColumnIndex();
            require(juniper >= 0, "Juniper command set missing after update");
            require("set cli screen-length 0".equals(commands.getRow(1).getCell(juniper).toString()), "Pagination setup must stay first");
            require("show version".equals(commands.getRow(2).getCell(juniper).toString()), "Existing users did not receive show version");
            require("quit".equals(commands.getRow(9).getCell(juniper).toString()), "Complete Juniper command sequence lost");
        }
        for (Path p : new Path[]{history, prefs})
            require("KEEP-USER-FILE".equals(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)), "User file changed: " + p);
        require(!Files.exists(zip), "Updater did not consume package");
        System.out.println("PASS ReleaseInstallRegression: installed and relaunched " + expected
                + "; inventory, credentials, custom data, history and preferences preserved; evidence=" + sandbox);
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
