package com.acard.acard.tools;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

public class LogTools {



    private static  LogTools instance = null;

    public static boolean isLog = false;
    public static boolean isLog2 = false;

    public static boolean isLog4 = false;
    public static boolean isLog5 = false;
    public static boolean isLog6 = true;
    private LogTools(){
        try {
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            String logPath = "runtime/c/c_" + timestamp + ".txt";

            File logFile = new File(logPath);
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            recordLogger = new PrintWriter(new FileWriter(logFile, true));

        } catch (Exception e) {
            System.err.println("日志初始化失败: " + e.getMessage());
        }

        try {
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            String logPath = "runtime/ct/c_" + timestamp + ".txt";

            File logFile = new File(logPath);
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            recordLogger2 = new PrintWriter(new FileWriter(logFile, true));

        } catch (Exception e) {
            System.err.println("日志初始化失败: " + e.getMessage());
        }

        try {
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            String logPath = "runtime/ct/slow_" + timestamp + ".txt";

            File logFile = new File(logPath);
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            recordLogger4 = new PrintWriter(new FileWriter(logFile, true));

        } catch (Exception e) {
            System.err.println("日志初始化失败: " + e.getMessage());
        }


        try {
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            String logPath = "runtime/ct/fps_" + timestamp + ".txt";

            File logFile = new File(logPath);
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            recordLogger5 = new PrintWriter(new FileWriter(logFile, true));

        } catch (Exception e) {
            System.err.println("日志初始化失败: " + e.getMessage());
        }
    }

    public static LogTools getInstance(){
         if(instance==null){
             instance = new LogTools();
         }
         return instance;
    }


    private  PrintWriter recordLogger;
    private  PrintWriter recordLogger2;
    private  PrintWriter recordLogger4;
    private  PrintWriter recordLogger5;
    public void logRecord(String message) {

        logRecord2(message);
    }

    public void logRecord2(String message) {


        if(isLog){
            String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSSSSS"));
            String logMsg = "[" + timestamp + "] " + message;
            System.out.println(logMsg);
            if (recordLogger2 != null) {
                recordLogger2.println(logMsg);
                recordLogger2.flush();
            }
        }
    }

    public void logRecord3(String message) {


        if(isLog2){
            String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSSSSS"));
            String logMsg = "[" + timestamp + "] " + message;
            System.out.println(logMsg);
            if (recordLogger2 != null) {
                recordLogger2.println(logMsg);
                recordLogger2.flush();
            }
        }
    }


    public void logRecord4(String message) {


        if(isLog4){
            String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSSSSS"));
            String logMsg = "[" + timestamp + "] " + message;
            System.out.println(logMsg);
            if (recordLogger4 != null) {
                recordLogger4.println(logMsg);
                recordLogger4.flush();
            }
        }
    }

    public void logRecord5(String message) {


        if(isLog5){
            String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSSSSS"));
            String logMsg = "[" + timestamp + "] " + message;
            System.out.println(logMsg);
            if (recordLogger4 != null) {
                recordLogger4.println(logMsg);
                recordLogger4.flush();
            }
        }
    }

    public void logRecord6(String message) {


        if(isLog6){
            String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSSSSS"));
            String logMsg = "[" + timestamp + "] " + message;
            System.out.println(logMsg);
            if (recordLogger4 != null) {
                recordLogger4.println(logMsg);
                recordLogger4.flush();
            }
        }
    }

}
