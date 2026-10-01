package com.java.botgetlog.truecorp;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Malformed device output must not hide complete or genuinely incomplete commands. */
public final class TranscriptEncodingRegression {
    private static int checks;
    private static Method validate;
    private static final String[] HW = {"HW-LLDP-Link_OPTIC", "screen-length 0 temporary",
        "display cpu-usage", "display memory-usage", "display lldp neighbor",
        "display version", "display interface", "quit"};
    private static final String[] NOKIA = {"N-LLDP-Link_OPTIC", "environment no more",
        "show system cpu", "show system memory-pools", "show system lldp neighbor",
        "show chassis", "show version", "show port", "show lag description", "logout"};

    public static void main(String[] args) throws Exception {
        validate=Telnet_Multi.class.getDeclaredMethod("hasCompleteCollectionTranscript",File.class,String.class,String[].class);
        validate.setAccessible(true);
        Path dir=Files.createTempDirectory("transcript-encoding-");
        try {
            for(String[] commands:new String[][]{HW,NOKIA}) {
                boolean nokia=commands==NOKIA;
                String prompt=nokia?"*B:CPE-TEST_1#":"<CPE-TEST_1>";
                File file=dir.resolve("[1]10.0.0.1_CPE-TEST-1_"+commands[0]+"_2026-10-01.txt").toFile();
                StringBuilder b=new StringBuilder();
                for(int i=1;i<commands.length;i++) {
                    b.append(prompt).append(commands[i]).append('\n');
                    if(i<commands.length-1)b.append("Response ").append(i).append('\n');
                    if(i==3)b.append("Description:DATA_BYTES\n");
                    if(commands[i].equals("show port"))b.append("1/1/c1/1 Up\n")
                        .append(prompt).append("show port 1/1/c1/1\nPort data\n")
                        .append(prompt).append("show port 1/1/c1/1 ethernet lldp remote-info\nNo peers\n");
                }
                String complete=b.toString();
                for(byte[] malformed:new byte[][]{{(byte)0xe0,(byte)0xb8},{(byte)0xff},{(byte)0x80},{(byte)0xe0,(byte)0xa1,(byte)0xe9}}) {
                    verify(file,commands,inject(complete,malformed),true);
                    verify(file,commands,inject(complete.replace(prompt+commands[2]+"\nResponse 2\n",""),malformed),false);
                    verify(file,commands,inject(complete.replace("Response 2\n",""),malformed),false);
                    verify(file,commands,inject(complete.replace(prompt+commands[commands.length-1]+"\n",""),malformed),false);
                    verify(file,commands,inject(complete.replace(prompt+commands[2],prompt.replace("CPE-TEST_1","OTHER-NODE")+commands[2]),malformed),false);
                    verify(file,commands,inject(complete.replace(prompt+commands[2],prompt+"DATA_BYTES"),malformed),false);
                    verify(file,commands,inject(complete.replace(prompt+commands[2],prompt.replace("CPE-TEST_1","CPE-DATA_BYTES")+commands[2]),malformed),false);
                    if(nokia)verify(file,commands,inject(complete.replace(prompt+"show port 1/1/c1/1\nPort data\n",""),malformed),false);
                }
                verify(file,commands,complete.replace("DATA_BYTES","ข้อความภาษาไทย").getBytes(StandardCharsets.UTF_8),true);
            }
            for(String arg:args) {
                Path input=Paths.get(arg);
                String name=input.getFileName().toString().replaceFirst("_deleted_.*\\.txt$",".txt");
                String[] commands=name.contains("_N-LLDP")?NOKIA:HW;
                byte[] bytes=Files.readAllBytes(input);
                // Preserve raw bytes: decoding fixtures before testing masked this bug.
                verify(dir.resolve(name).toFile(),commands,bytes,true);
            }
            System.out.println("PASS TranscriptEncodingRegression "+checks+" checks");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p->{try {Files.delete(p);}catch(IOException e){throw new UncheckedIOException(e);}});
            }
        }
    }
    private static byte[] inject(String text,byte[] bytes)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        String[] parts=text.split("DATA_BYTES",-1);
        for(int i=0;i<parts.length;i++){if(i>0)out.write(bytes);out.write(parts[i].getBytes(StandardCharsets.UTF_8));}
        return out.toByteArray();
    }
    private static void verify(File file,String[] commands,byte[] bytes,boolean expected)throws Exception {
        Files.write(file.toPath(),bytes);
        boolean actual=(Boolean)validate.invoke(null,file,commands[0],commands);
        checks++;
        if(actual!=expected)throw new AssertionError("Check "+checks+": expected "+expected+" got "+actual);
        if(!Arrays.equals(bytes,Files.readAllBytes(file.toPath())))throw new AssertionError("Validator changed log bytes");
    }
}
