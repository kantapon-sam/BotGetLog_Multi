# BotGetLog_Multi

ชุดเครื่องมือ Java สำหรับเก็บและประมวลผล Network Logs ของ TRUE / DTAC
เปิดใช้งานผ่าน **Bot Tool Launcher** หรือใช้ collector ร่วมกับ Linux scheduler และ TrueLogs

[ดาวน์โหลดรุ่นล่าสุด](https://github.com/kantapon-sam/BotGetLog_Multi/releases/latest) ·
[สารบัญเอกสาร](docs/README.md) ·
[ประวัติรุ่น](docs/releases/README.md)

## เริ่มใช้งาน Desktop

1. ดาวน์โหลด **portable ZIP** แล้วแตกไฟล์ เปิด `run.bat` เพื่อใช้ Java ที่มาพร้อมแพ็กเกจ
2. ถ้าใช้ **dist ZIP** ให้ติดตั้ง Java 8 แล้วเปิด `Bot Tool Launcher.jar` พร้อมโฟลเดอร์ที่มากับแพ็กเกจ
3. ตั้งค่าบัญชีและไฟล์งานในโปรแกรมก่อนเริ่มเก็บข้อมูล ดูรายการเครื่องมือและไฟล์ที่ต้องใช้ใน [README.TXT](README.TXT)
4. เมื่อมีรุ่นใหม่ โปรแกรมตรวจ Auto Update ตอนเปิดและให้ผู้ใช้ยืนยันก่อนติดตั้ง

Auto Update เก็บไฟล์งาน `UserInterface_Input.xlsx`, ผลลัพธ์ `_output` และการตั้งค่า
`launcher-data` ไว้ ดู [รายละเอียด Auto Update](docs/maintainers/auto-update.md)

## คู่มือที่ใช้บ่อย

| เรื่อง | เอกสาร |
| --- | --- |
| บัญชีสำรองและลำดับการเข้า TrueLogs | [CLLS backup accounts](docs/guides/clls-backup-accounts.md) |
| ตั้งค่า ZTE Enable | [ZTE Enable](docs/guides/zte-enable-negotiation.md) |
| Live PN monitoring | [Live PN monitor](docs/guides/live-pn-monitor.md) |
| งานเซิร์ฟเวอร์และ daily budget | [คู่มือผู้ดูแลระบบ](docs/README.md#operations) |
| Build และเผยแพร่รุ่น | [Release workflow](docs/maintainers/releasing.md) |

TrueLogs ลอง SAM/CLLS ที่ผู้ใช้บันทึกในหน้า **Your SAM & CLLS login** ก่อน
เมื่ออุปกรณ์ปฏิเสธบัญชีอย่างชัดเจน จึงใช้ Backup 1–5 ที่ Control บันทึกไว้ตามลำดับ
โดยยังใช้ SAM ของเจ้าของงาน และไม่ใช้บัญชีหลักของ Control

เอกสารทั้งหมดอยู่ใน `docs/` ส่วน `README.TXT` คงไว้สำหรับแพ็กเกจ Desktop
และ [AGENTS.md](AGENTS.md) เป็นข้อตกลงสำหรับงานใน repository
