package com.java.botgetlog.truecorp;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
/** Loopback-only SSH failures verify pacing on the shared scheduled/live connection entry. */
public class GatewayPacingIntegrationRegression {
 public static void main(String[] args)throws Exception {
  Path dir=Files.createTempDirectory("gateway-entry-qa-");System.setProperty("true.log.gatewayPacing.dir",dir.toString());
  try(ServerSocket server=new ServerSocket(0,8,InetAddress.getLoopbackAddress())){
   List<Long> accepted=Collections.synchronizedList(new ArrayList<Long>());
   Thread peer=new Thread(()->{try{for(int i=0;i<2;i++)try(Socket socket=server.accept()){accepted.add(System.currentTimeMillis());}}catch(Exception e){throw new RuntimeException(e);}});peer.setDaemon(true);peer.start();
   Method parse=Telnet_Multi.class.getDeclaredMethod("parseGatewayEndpoint",String.class);parse.setAccessible(true);Object endpoint=parse.invoke(null,"ssh://127.0.0.1:"+server.getLocalPort());
   Field modes=Telnet_Multi.class.getDeclaredField("SSH_AUTH_MODES");modes.setAccessible(true);Object mode=((Object[])modes.get(null))[0];
   Method open=Telnet_Multi.class.getDeclaredMethod("openSshGatewayAttempt",endpoint.getClass(),String.class,String.class,mode.getClass());open.setAccessible(true);
   for(int i=0;i<2;i++)try{open.invoke(null,endpoint,"synthetic-sam","synthetic-secret",mode);throw new AssertionError("Expected loopback SSH closure");}catch(InvocationTargetException expected){if(!(expected.getCause() instanceof Exception))throw expected;}
   peer.join(2000);if(accepted.size()!=2||accepted.get(1)-accepted.get(0)<4800)throw new AssertionError("Physical SSH entry bypassed cooldown: "+accepted);
   Thread.currentThread().interrupt();try{open.invoke(null,endpoint,"synthetic-sam","synthetic-secret",mode);throw new AssertionError("Interrupted open proceeded");}catch(InvocationTargetException expected){if(!(expected.getCause() instanceof InterruptedException))throw expected;}finally{Thread.interrupted();}
   System.out.println("PASS GatewayPacingIntegrationRegression: real loopback SSH entry cooldown and cancellation");
  }
 }
}
