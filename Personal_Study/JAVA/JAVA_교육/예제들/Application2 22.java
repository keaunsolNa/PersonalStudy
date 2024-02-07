package com.greedy.section03.uses;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

public class Application2 {
	public static void main(String[] args) {
		
		/* try~with~resource */
		/*
		 * JDK 1.7에서 추가 된 문법이다.
		 * close 해야하는 인스턴스(스트림)의 경우 try 옆에 소괄호 안에서 생성하면
		 * 해당 try-catch 블럭이 완료될 때 자동으로 close 처리 해 준다.
		 */
		
		try(BufferedReader in = new BufferedReader(new FileReader("test.dat"))){
			String s;
			
			while((s = in.readLine()) != null) {
				System.out.println(s);
			}
		} catch(FileNotFoundException e) {
			e.printStackTrace();
		} catch(IOException e) {
			e.printStackTrace();
		}
	}

}
