package com.java.botgetlog.truecorp;
public class LiveVendorSelectionRegression {
 public static void main(String[] args)throws Exception{
  java.nio.file.Path settings = java.nio.file.Files.createTempDirectory("live-enable-settings-");
  System.setProperty("botgetlog.enable.settingsDir", settings.toString());
  privilege("CPE-ZTE>","",false);
  com.java.shared.DeviceEnableSettings store = new com.java.shared.DeviceEnableSettings(settings);
  java.util.List<java.util.Map<String,String>> entries = new java.util.ArrayList<>();
  for(String value : new String[]{"synthetic-primary", "synthetic-backup"}) { java.util.Map<String,String> entry = new java.util.HashMap<>(); entry.put("password",value); entries.add(entry); }
  store.save("ZTE",(String)store.status().get("revision"),entries,2,false);
  check("CPE-CBRA469>","N-ARP","ZTE-ARP");check("CPE-ZTE#","N-PTP","ZTE-PTP");
  check("A:CPE-NOKIA#","ZTE-ARP","N-ARP");check("<CPE-HW>","N-LLDP-Link_OPTIC","HW-LLDP-Link_OPTIC");
  check("","N-ARP","N-ARP");check("Welcome to gateway","N-ARP","N-ARP");
  privilege("CPE-ZTE>","Password:\nCPE-ZTE#\n",true);privilege("CPE-ZTE>","CPE-ZTE>\n",false);privilege("CPE-ZTE#","",true);
  privilege("CPE-ZTE>","Password:\nInvalid password\nCPE-ZTE>\nPassword:\nCPE-ZTE#\n",true);
  privilege("CPE-ZTE>","Password:\nInvalid password\nPassword:\nCPE-ZTE#\n",true);
  privilege("CPE-ZTE>","Password:\nInvalid password\nPassword:\nInvalid password\nCPE-ZTE>\n",false);
  java.nio.file.Files.delete(settings.resolve("device-enable.enc"));
  privilege("CPE-ZTE>","",false);
  System.out.println("PASS LiveVendorSelectionRegression: authenticated prompt selects matching command type");
 }
 static void check(String prompt,String original,String expected){String actual=Telnet_Multi.commandSetForPrompt(prompt,original);if(!expected.equals(actual))throw new AssertionError(prompt+": "+actual+" != "+expected);}
 static void privilege(String prompt,String response,boolean expected)throws Exception{
  Class<?> type=Class.forName("com.java.botgetlog.truecorp.Telnet_Multi$CredentialProbe");java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object probe=ctor.newInstance();
  java.io.ByteArrayOutputStream written=new java.io.ByteArrayOutputStream();
  for(String name:new String[]{"in","out","lastPromptToken"}){java.lang.reflect.Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(probe,name.equals("in")?new java.io.ByteArrayInputStream(response.getBytes("UTF-8")):name.equals("out")?new java.io.PrintStream(written):prompt);}
  java.lang.reflect.Method enable=type.getDeclaredMethod("ensureZtePrivilegedMode");enable.setAccessible(true);if(!Boolean.valueOf(expected).equals(enable.invoke(probe)))throw new AssertionError("Privilege prompt was not verified");
  if(prompt.endsWith("#")&&written.size()!=0)throw new AssertionError("Existing privileged session was changed");
  if(response.isEmpty()&&!expected&&written.size()!=0)throw new AssertionError("Missing settings sent data to device");
 }
}
