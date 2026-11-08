package com.java.botgetlog.truecorp;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.truelinkoptical.shared.SharedConnectionBudget;

/** Personal-first policy and Control capacity, with no network or real credentials. */
public final class TrueLogsControlFallbackRegression {
 static int checks;
 static final String REJECT="% Username or password error\r\nUsername:";
 static class Fake extends Telnet_Multi.CredentialProbe {
  final List<String> tried=new ArrayList<>(); final String primary,backup;
  Fake(String primary,String backup){this.primary=primary;this.backup=backup;}
  @Override Telnet_Multi.LoginValidationResult validate(String g,String su,String sp,String ip,String u,String p,String cmd,String node){
   tried.add(u);String reply=tried.size()==1?primary:backup;
   try {set(this,"personalCredentialSubmitted",true);set(this,"lastAuthenticationResponse",reply);set(this,"lastPromptToken",reply);}
   catch(Exception e){throw new RuntimeException(e);}
   return reply.endsWith("#")?Telnet_Multi.LoginValidationResult.success("ok"):Telnet_Multi.LoginValidationResult.invalidCredentials("rejected");
  }
 }
 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory("control-fallback-regression-");Properties before=(Properties)System.getProperties().clone();
  try {
   File store=root.resolve("credentials.properties").toFile();
   check(TrueCllsCredentialStore.save(store,"control-primary-must-not-be-used","control-primary-secret"),"save distinct Control primary to exclude");
   check(TrueCllsCredentialStore.saveBackup(store,1,"who","synthetic-who"),"save synthetic backup");
   check(TrueCllsCredentialStore.saveBackup(store,2,"other","synthetic-other"),"save second backup");
   SharedConnectionBudget shared=new SharedConnectionBudget(root.resolve("shared"),20,5);
   Files.createDirectories(root.resolve("personal"));
   System.setProperty("botgetlog.live.cllsBackupStore",store.toString());
   System.setProperty("botgetlog.live.cllsBackupBudgetDir",root.resolve("shared").toString());
   System.setProperty("true.shared.connectionBudget.dir",root.resolve("personal").toString());
   System.setProperty("true.log.progress.file",root.resolve("progress.properties").toString());
   try(Fake f=new Fake("QA-NODE#","OTHER#")){
    check(run(f).isSuccess(),"personal success preserved");check(f.tried.equals(Arrays.asList("personal")),"personal success does not try Control");check(shared.snapshot().botActive==0,"no Control lease for personal success");
   }
   for(String blocked:Arrays.asList("[TIMEOUT-READ]","Connection closed by foreign host",REJECT+"account locked",REJECT+"the IP has been blocked",REJECT+"[TIMEOUT-READ]"))
    try(Fake f=new Fake(blocked,"QA-NODE#")){check(!run(f).isSuccess(),"failure preserved");check(f.tried.size()==1,"no backup on lock/timeout/disconnect alone");}
   try(Fake f=new Fake(REJECT+"Connection closed by foreign host","QA-NODE#")){
    check(run(f).isSuccess(),"closed rejected personal login reaches backup");check(f.tried.equals(Arrays.asList("personal","who")),"personal before Control who; Control primary excluded");
    check(shared.snapshot().botActive==1,"separate personal capacity additionally holds Control permit");
    check(Boolean.TRUE.equals(get(f,"forceZteEnableAfterLogin")),"all ZTE backups explicitly enable");
    Method redact=Telnet_Multi.CredentialProbe.class.getDeclaredMethod("redactControlBackups",String.class);redact.setAccessible(true);
    check("[REDACTED] [REDACTED]".equals(redact.invoke(f,"synthetic-who synthetic-other")),"all Control backup secrets redacted");
   }
   check(shared.snapshot().botActive==0,"Control permit released after node closes");
   try(Fake f=new Fake(REJECT,"OTHER-NODE#")){check(!run(f).isSuccess(),"wrong node cannot succeed");check(f.tried.size()==2,"wrong identity stops credential sequence");}
   try(Fake f=new Fake(REJECT,REJECT)){check(!run(f).isSuccess(),"exhausted Control backups fail");check(f.tried.equals(Arrays.asList("personal","who","other")),"Control order bounded");}
   System.setProperty("true.shared.connectionBudget.dir",root.resolve("shared").toString());
   try(SharedConnectionBudget.Lease existing=shared.tryAcquireBot();Fake f=new Fake(REJECT,"QA-NODE#")){
    check(existing!=null&&run(f).isSuccess(),"shared account fallback succeeds");check(shared.snapshot().botActive==1,"shared job reuses held permit");
   }
   System.clearProperty("botgetlog.live.cllsBackupStore");
   try(Fake f=new Fake(REJECT,"QA-NODE#")){check(!run(f).isSuccess()&&f.tried.size()==1,"unconfigured clients keep personal-only behavior");}
   System.out.println("PASS TrueLogsControlFallbackRegression checks="+checks);
  }finally{System.setProperties(before);try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}}
 }
 static Telnet_Multi.LoginValidationResult run(Fake f)throws Exception{
  Method m=Telnet_Multi.CredentialProbe.class.getDeclaredMethod("validateWithControlBackups",String.class,String.class,String.class,String.class,String.class,String.class,String.class,String.class);m.setAccessible(true);
  return (Telnet_Multi.LoginValidationResult)m.invoke(f,"gateway","personal-sam","sam-secret","192.0.2.1","personal","personal-secret","ZTE-LLDP-Link_OPTIC","QA-NODE");
 }
 static void set(Object o,String n,Object v)throws Exception{Field f=Telnet_Multi.CredentialProbe.class.getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
 static Object get(Object o,String n)throws Exception{Field f=Telnet_Multi.CredentialProbe.class.getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
}
