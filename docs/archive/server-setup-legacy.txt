TRUE Link Optical Auto on LLDP MapViewer server

Linux server:
1. Copy this BotGetLog_Multi folder to /home/transportsftp/BotGetLog_Multi.
2. Run chmod +x *.sh.
3. Run ./save_true_clls_credentials.sh once on the server user.
4. Run ./run_true_linkoptical_auto_allsite_to_mapviewer.sh once to verify:
   - Bot logs are created under dist/_output/Total_Log
   - Link Optical CSV files are created under dist/_output/LLDP_Neighbor
   - Latest CSV files are copied to /home/transportsftp/LLDP_MapViewer/_input
5. Run ./install_true_linkoptical_cron.sh to install a daily cron job.
   The default schedule is daily at 02:00.

Windows server:
1. Copy this BotGetLog_Multi folder to the LLDP MapViewer server.
2. Confirm the LLDP MapViewer input folder. If it is not a sibling folder named
   LLDP_MapViewer\_input, edit Run_TRUE_LinkOptical_Auto_AllSite_To_MapViewer.bat
   and set MAPVIEWER_INPUT_DIR to the real _input path.
3. Run BotGetLog_Multi.bat once from the server desktop.
4. Run TRUE Link Optical Auto once manually and save/validate the CLLS credential
   on that server user.
5. Run Run_TRUE_LinkOptical_Auto_AllSite_To_MapViewer.bat once to verify.
6. Run Install_TRUE_LinkOptical_Auto_Task.bat to install a daily scheduled task.
   The default schedule is daily at 02:00.

No server upload is performed by these scripts. They run and copy files only on
the machine where this folder is installed.
