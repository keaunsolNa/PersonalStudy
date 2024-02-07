package com.greedy.section01.file;

import java.io.File;
import java.io.IOException;

public class Application1 {
	public static void main(String[] args) {

		/* File을 이용한 스트림을 사용하기 앞서 File 클래스의 기본 사용 방법을 확인해 보자. */
		
		/*
		 * JDK 1.0부터 지원하는 API로 파일 처리를 수행하는 대표적인 클래스이다.
		 * 대상 파일에 대한 정보로 인스턴스를 생성하고
		 * 파일의 생성, 삭제 등등의 처리를 수행하는 기능을 제공하고 있다.
		 */
		
		/*
		 * 파일 클래스를 이용해서 파일 인스턴스를 생성한다.
		 * 대상 경로에 파일이 존재하지 않아도 인스턴스는 생성할 수 있다.
		 */
		File file = new File("src/com/greedy/section01/file/test.txt");
				
		/* 실제 파일을 File 인스턴스로 생성해 보자. */
		try {
			boolean createSuccess = file.createNewFile();
			
			System.out.println("파일 생성 여부: " + createSuccess);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		System.out.println("파일의 크기 : " + file.length() + "byte");
		System.out.println("파일의 경로 : " + file.getPath());
		System.out.println("현재 파일의 상위 경로 : " + file.getParent());
		System.out.println("파일의 절대 경로: " + file.getAbsolutePath());
		
		/* 파일 삭제 */
		boolean deleteSuccess = file.delete();
		
		System.out.println("파일 삭제 여부: " + deleteSuccess);
		
	}

}
