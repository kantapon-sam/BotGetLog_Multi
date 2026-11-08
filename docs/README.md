# สารบัญเอกสาร

[กลับหน้าหลัก](../README.md)

## คู่มือผู้ใช้

| เอกสาร | เนื้อหา |
| --- | --- |
| [CLLS backup accounts](guides/clls-backup-accounts.md) | ลำดับบัญชีหลัก/สำรอง, slow authentication, TrueLogs และการรักษา log |
| [CLLS note recovery](guides/clls-note-recovery.md) | เงื่อนไขปิด Note หลังบัญชีหลักกลับมาเข้าได้ |
| [ZTE Enable](guides/zte-enable-negotiation.md) | การยืนยัน privileged mode และรหัส Enable |
| [ZTE partial collection](guides/zte-partial-collection.md) | การเก็บบางคำสั่งไม่ครบและการรายงานผล |
| [Live PN monitor](guides/live-pn-monitor.md) | คำสั่งและผลลัพธ์สำหรับ ZTE PN |

## Operations

Linux scheduler, cron และ daily-policy helpers ใช้ source ที่
[LLDP_MapViewer / OPERATIONS](https://github.com/kantapon-sam/LLDP_MapViewer/blob/main/docs/OPERATIONS.md)
เป็นหลัก ตารางปัจจุบันคือ daily **19:00** และ Friday Full **18:00** เวลาไทย
สคริปต์ scheduler รุ่นเก่าใน repository นี้ไม่ใช่ต้นฉบับสำหรับ deploy

- [Daily distinct-IP budget](operations/daily-unique-node-budget.md): การนับ IP, retries และข้อยกเว้น
- [Collection-cycle design](operations/collection-cycle-2000.md): การตรึงวันของรอบงาน พร้อมหมายเหตุการเปลี่ยนเวลา
- [TrueLogs service/runtime](https://github.com/kantapon-sam/LLDP_MapViewer/blob/main/true-log-console/README.md): runtime ที่แต่ละ job ใช้และการเผยแพร่ worker

การอัปเดตโปรแกรมไม่รีเซ็ต budget และไม่ลบ log โดยอัตโนมัติ สำหรับ Full ที่ต้องเก็บไฟล์เดิม
ให้ใช้ขั้นตอน preservation ใน OPERATIONS และตรวจทั้ง collector, merger และ publisher flags
การรีเซ็ต budget เป็นคำสั่งแยกที่ต้องใช้ภายใต้ workflow lock เดิม

## สำหรับผู้พัฒนา

| เอกสาร | เนื้อหา |
| --- | --- |
| [Release workflow](maintainers/releasing.md) | Build, ตรวจแพ็กเกจ, GitHub Release, Desktop และ TrueLogs |
| [Auto Update](maintainers/auto-update.md) | Manifest, ดาวน์โหลด, checksum และไฟล์ที่คงไว้ |
| [Auto-added device identity](maintainers/auto-added-device-identity.md) | ตรวจชื่อและชนิดอุปกรณ์ที่ค้นพบใหม่ |
| [Scheduled SSH reader](maintainers/scheduled-ssh-reader.md) | การอ่านผลลัพธ์ SSH ของ scheduled collector |
| [SSH pagination](maintainers/ssh-pagination.md) | Pager และ prompt boundary |
| [TrueLogs buffered reader](maintainers/truelogs-buffered-command-reader.md) | Buffered reads และการทดสอบ |
| [Juniper completeness](maintainers/juniper-log-completeness.md) | เงื่อนไขตรวจความครบถ้วนของ Juniper logs |

## ประวัติ

- [Release notes](releases/README.md): บันทึกเดิมจัดเรียงตามเวอร์ชัน และลิงก์ GitHub Releases
- [เอกสารเก่า](archive/README.md): คู่มือที่เก็บไว้อ้างอิงประวัติ ใช้เส้นทางและขั้นตอนจากเอกสารปัจจุบันเมื่อ deploy

เพิ่มคู่มือใหม่ในหมวดที่ตรงกับงานและเพิ่มลิงก์ในสารบัญนี้ ส่วน release notes ให้เก็บใน
`docs/releases/` เพื่อให้หน้าหลัก repository อ่านง่าย
