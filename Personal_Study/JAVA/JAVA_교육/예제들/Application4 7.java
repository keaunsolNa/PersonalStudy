package com.greedy.section02.stream;

import java.io.FileWriter;
import java.io.IOException;

public class Application4 {
	public static void main(String[] args) {

		/* FileWriter */
		/*
		 * 프로그램의 데이터를 파일로 내보내기 위한 용도의 스트림이다.
		 * 1문자 단위(3byte<-utf-8)로 데이터를 처리한다.
		 */
		
		FileWriter fw = null;
		
		try {
			fw = new FileWriter("src/com/greedy/section02/stream/testWriter.txt");
			
			fw.write(97);
			fw.write('A');
			fw.write(new char[] {'a','p','p','l','e'});
			fw.write("\n세종대왕 만만세!");
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if(fw != null) {
					fw.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

}
