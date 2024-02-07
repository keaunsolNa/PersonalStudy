package com.greedy.section03.map.run;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public class Application2 {
	public static void main(String[] args) {

		/* Properties */
		/*
		 * 설정 파일의 값을 읽어서 어플리케이션에 적용할 때 사용
		 * hashMap처럼 key와 value를 저장하는데 key와 value 모두 
		 * String을로만 되어 있어야 한다.
		 */
		
		Properties prop = new Properties();
		
		prop.setProperty("driver", "aoracle.jdbc.driver.OracleDriver");
		prop.setProperty("url", "jdbc:oracle:thin:@127.0.0.1:1521:xe");
		prop.setProperty("user", "student");
		prop.setProperty("password", "student");
		
		System.out.println("파일에 저장 전 Properties: " + prop);
		
		/*
		 * dat : 응용프로그램에서 처리 할 정보가 들어있을 시 적용하는 파일의 확장자
		 * xml(eXtensible Markup Language) 데이터를 전달하거나 교환하기 위해 태그(<>)로 이루어진 데이터 교환 포맷.
		 */
		try {
//			prop.store(new FileOutputStream("driver.dat"), "jdbc driver");
//			prop.store(new FileOutputStream("driver.txt"), "jdbc driver");
			prop.storeToXML(new FileOutputStream("driver.xml"), "jdbc driver");
			
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		/* 파일로부터 읽어와서 새로운 Properties 객체에 기록 */
		Properties prop2 = new Properties();
		
		try {
//			prop2.load(new FileInputStream("driver.dat"));
			prop2.load(new FileInputStream("driver.txt"));
			prop2.loadFromXML(new FileInputStream("driver.xml"));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		System.out.println("파일에 저장 후 Properties: " + prop2);
		System.out.println(prop2.getProperty("driver"));
		System.out.println(prop2.getProperty("url"));
		System.out.println(prop2.getProperty("user"));
		System.out.println(prop2.getProperty("password"));
		
		
		
	}
}
