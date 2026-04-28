package com.java.myapp.arp;

import java.io.FileWriter;
import java.io.IOException;

public class Writer {

    public static void Str_Sum(FileWriter Str_Sum, String str2) {
        try {
            Str_Sum.write(str2);
            Str_Sum.close();
            System.out.println("Write Success");
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    static void A(FileWriter A, String str1) {
        try {
            A.write(str1);
            A.close();
            System.out.println("Write Success");
        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    
}
