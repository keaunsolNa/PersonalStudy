package com.greedy.section03.uses;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

public class Application1 {
	public static void main(String[] args) {
		
		/* 
		 * 예외처리를 가장 많이 활용하는 것이 IO(Input / Output) 관련 패키지이다.
		 * 아직 IO는 배우지 않았지만 IO문법보다는 try-catch블럭의 실제 사용과
		 * 흐름에 집중해 보자. 
		 */
		
		BufferedReader in =  null;
		try {
			
			/*
			 * FileReader라는 클래스의 생성자에 예외를 throws 해 놓았다.
			 * 사용하는 쪽에서는 반드시 예외처리를 해야 쓸 수 있기 때문에
			 * try-catch블럭 안에서 생상자를 호출하며 인스턴스를 생성해야 한다.
			 */
			in = new BufferedReader(new FileReader("text.dat"));
			
			String s;
			
			/*
			 * readLine 이라는 메소드도 API를 보면 IOException을 발생시켜 놓았기 때문에
			 * catch블럭을 추가해서 예외처리 구문을 작성해야 한다. 
			 */
			while((s = in.readLine()) != null) {
				System.out.println(s);
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			
			/*
			 * 예외 처리 구문과 상관없이 반드시 수행해야 하는 경우 작성을 하며
			 * 보통은 사용한 자원을 반납할 목적으로 사용하게 된다. 
			 */
			try {
				
				/*
				 * 스트림이 생성되지 않은 상태에서 close를 하게 되면 NullPointerException이 발생한다.
				 * 파일을 찾지 못해 객체를 생성하지 못하고 레퍼런스 변수는 null값을 가지고 있는데
				 * null인 상태에서 참조연산자(.)를 사용 시 발생하게 되는 예외이다.
				 * NullPointerException은 unchecked Exception으로 try-catch해서 처리하기 보다는 
				 * 보통은 if-else 구문으로 해결하게 된다.
				 */
//				in.close();						// 런타임시 NullPointerException 예외 발생
				if(in != null) {
					
					/*
					 * 입출력에 사용한 스트림을 닫아 주는 메소드이다.
					 * API에서 확인해 보면 IOException을 위임한 메소드이기 때문에
					 * finally 블럭 안이라도 또 예외처리를 중첩으로 해 주어야 한다.
					 * try 블럭과 finally블럭은 별개이기 때문이다. 
					 */
					in.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	} 
}
