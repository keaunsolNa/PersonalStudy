package com.greedy.section03.filterstream;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class Application1 {
	public static void main(String[] args) {

		/*
		 * java.io 패키지의 입출력 스트림은 기본 스트림과 필터 스트림으로 분류할 수 있다.
		 * 기본 스트림은 외부 데이터에 직접 연결되는 스트림이고
		 * 필터 스트림은 외부 데이터에 직접 연결하는 것이 아니라 기본 스트림에 추가로
		 * 사용할 수 있는 스트림이다.
		 * 주로 성능을 향상시키는 목적으로 사용되며 생산자를 보면 구분이 가능하다.
		 * 생산자 쪽에 매개변수로 다른 스트림을 이용하는 클래스는 필터스트림이라고 볼 수 있다.
		 */
		
		/*
		 * 버퍼를 이용해서 성능 향상을 시키는 보조 스트림을 사용해 보자. (Reader나 Writer 기준)
		 * BufferedWriter / BufferedReader
		 */
		
		BufferedWriter bw = null;
		
		try {
			bw = new BufferedWriter(new FileWriter
					("src/com/greedy/section03/filterstream/testBuffered.txt"));
			
			bw.write("안녕하세요\n");
			bw.write("반갑습니다.\n");
			
			/*
			 * 버퍼를 이용하는 경우 버퍼가 가득 차면 자동으로 내보내기를 하지만
			 * 버퍼가 가득 차지 않은 상태에서는 강제로 내보내기를 해야 한다.
			 * close() 하지 않고 확인해 보면 파일에 기록되지 않는 것을 볼 수 있는데
			 * 이 때 flush()를 해 주면 파일에 버퍼에 있던 출력하려는 내용이 기록된다.
			 */
			bw.flush();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if(bw != null) {
					
					/* close()를 호출하면 내부적으로 flush()를 하고 나서 기본 스트림 및 보조 스트림의 자원을 반납한다. */
					bw.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		BufferedReader br = null;
		try {
			br = new BufferedReader(
					new FileReader("src/com/greedy/section03/filterstream/testBuffered.txt"));
	
			String temp = "";
			
			while((temp = br.readLine()) != null ) {
				System.out.println(temp);
			}
			
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if(br != null) {
					br.close(); 
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
				
	}
}