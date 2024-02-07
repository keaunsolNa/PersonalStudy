package com.greedy.section02.stream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;

public class Application1 {
	public static void main(String[] args) {

		/* FileInputStream */

		/* 같은 패키지에 존재하는 testInputStream.txt 파일을 대상으로 파일을 읽어올 수 있도록 스트림 인스턴스 생성. */
		FileInputStream fin = null;
		try {
			fin = new FileInputStream("src/com/greedy/section02/stream/testInputStream.txt");
			
			/*
			 * read() : 파일에 기록된 값을 순차적으로 읽어오고 더 이상 읽어올 데이터가 없는 경우 -1을 반환
			 * 읽어온 한 바이트는 문자로 인식하고 가져온 것이므로 반환되는 int값은 유니코드 번호에 해당된다.
			 */
//			int value = 0;
//			
//			while((value = fin.read()) != -1) {
//				
//				/* 1바이트씩 읽어온 정수를 변수에 대입하고 출력하기 */
//				System.out.println((char) value);		// int값은 유니코드 번호이므로 char로 다운캐스팅
//			}
//			
			/*
			 * 한글은 한 글자에 utf-8 인코딩 방식의 경우 3바이트이기 때문에 3바이트 데이터를
			 * 1바이트씩 끊어서 읽어오면 글자가 깨지게 된다.
			 */
			
			 long fileLength =
					 new File("src/com/greedy/section02/stream/testInputStream.txt").length();
			 
			 /* 파일의 길이만큼의 byte 배열을 만든다. */
			byte[] bar = new byte[(int)fileLength];
			
			fin.read(bar);
			
//			System.out.println(Arrays.toString(bar));
			for(byte b : bar) {
				System.out.println((char)b);
			}
			
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if(fin != null) {			// fin 인스턴스(스트림)이 null이 아닌 경우 자원 반납을 해야 한다.
					
					/*
					 * 자원 반납을 해야 하는 이유
					 * 1. 장기간 실행 중인 프로그램에서 스트림을 닫지 않는 경우 다양한 리소수에서 누수(leak)가 
					 * 	  발생한다.
					 * 2. 뒤에서 배우는 버퍼를 사용하는 경우 마지막에 flush()로 버퍼에 있는 데이터를 강제로
					 *    전송해야 한다. 만약 잔류 데이터가 남은 상황에서 추가로 스트림을 사용한다면 데드락
					 *    (deadlock) 상태가 된다.
					 *    판단하기 어렵고 의도하지 않은 상황에서도 이런 현상이 발생할 수 있기 때문에 마지막에는
					 *    flush()를 무조건 실행해 주는 것이 좋다.
					 *    close() 메소드는 자원을 반납하며 flush()를 해주기 때문에 close()만 제대로 해
					 *    줘도 된다. 따라서 close() 메소드는 외부 자원을 사용하는 경우 반드시 마지막에
					 *    호출해 줘야 한다.
					 *    
					 *    try~with~resource 구문을 사용하면 마지막에 close()를 자동으로 호출해 주기 때문에
					 *    finally 구문을 쓸 필요가 없다.
					 */
					fin.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

}
