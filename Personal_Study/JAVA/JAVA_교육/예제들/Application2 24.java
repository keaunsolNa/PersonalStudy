package com.greedy.section03.filterstream;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

public class Application2 {
	public static void main(String[] args) {

		/*
		 * 표준 입출력 스트림
		 * 자바에선 콘솔이나 키보드 같은 표준 입출력 장치로부터 데이터를 입출력하기 위한 스트림을
		 * 표준 스트림 형태로 제공하고 있다. System 클래스의 필드 in, out, err가 대상 데이터와 
		 * 연결된 스트림을 의미한다.
		 * 
		 * System.in (InputStream) : 콘솔로부터 데이터를 입력받는다.
		 * System.out (PrintStream) : 콘솔로 데이터를 출력한다.
		 * System.err (PrintStream) : 콘솔로 데이터를 출력한다.
		 * 
		 * 자주 사용되는 자원에 대해 미리 스트림을 완성해 두었기 때문에 별도로 개발자가 스트림을 
		 * 생성하지 않아도 된다. 
		 */

		/* Scanner 같은 스트림 만들어 보자. */
		BufferedReader br = new BufferedReader(new InputStreamReader (System.in));
		
		System.out.println("문자열 입력: ");
		try {
			String value = br.readLine();
			
			System.out.println("value : " + value);
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

		/* System.out.print 같은 스트림 만들어 보자. */
		BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(System.out));
		
		try {
			bw.write("java oracle jdbc \n 화이팅!");
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if(bw != null) {
					bw.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	
		/* 콘솔로 빨간 글씨로 출력 */
		System.err.println("에러!!");
	}
}
