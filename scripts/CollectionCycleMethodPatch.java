import java.nio.file.*;
import java.util.*;
import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;

/** Java 8 deployment helper: transplant only the reviewed cycle-date methods.
 * Keeps installed transport fixes and every other method/field unchanged.
 * Compile with javac -XDignore.symbol.file on the deployment JDK 8.
 */
public class CollectionCycleMethodPatch {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("installed.class compiled.class output.class class-name");
        Set<String> selected = new HashSet<>();
        if (args[3].equals("Telnet_Multi")) {
            selected.add("currentDailyLogDateTag()Ljava/lang/String;");
            selected.add("resolveActiveLogDateTag(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;");
            selected.add("buildDailyLogFileNameForDeviceName(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;");
        } else if (args[3].equals("BotGetLog_TrueCorp")) {
            selected.add("isLogFileForToday(Ljava/lang/String;)Z");
        } else throw new IllegalArgumentException("Unexpected class");
        ClassReader original = new ClassReader(Files.readAllBytes(Paths.get(args[0])));
        ClassNode replacement = new ClassNode(Opcodes.ASM5);
        new ClassReader(Files.readAllBytes(Paths.get(args[1]))).accept(replacement, 0);
        if (!original.getClassName().equals(replacement.name)) throw new IllegalArgumentException("Class mismatch");
        Map<String,MethodNode> methods = new HashMap<>();
        for (Object item : replacement.methods) {
            MethodNode method = (MethodNode)item;
            if (selected.contains(method.name+method.desc)) methods.put(method.name+method.desc,method);
        }
        if (!methods.keySet().equals(selected)) throw new IllegalArgumentException("Replacement methods missing");
        ClassWriter writer = new ClassWriter(original, 0);
        Set<String> changed = new HashSet<>();
        original.accept(new ClassVisitor(Opcodes.ASM5,writer) {
            public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                MethodNode method=methods.get(name+desc);
                if (method==null) return super.visitMethod(access,name,desc,signature,exceptions);
                if (access!=method.access) throw new IllegalArgumentException("Method access changed");
                method.accept(writer);changed.add(name+desc);return null;
            }
        },0);
        if (!changed.equals(selected)) throw new IllegalArgumentException("Installed methods missing");
        Files.write(Paths.get(args[2]),writer.toByteArray());
        System.out.println(args[3]+": patched "+changed.size()+" cycle-date methods; retained installed transport code.");
    }
}
