package com.greedy.section03.filterstream;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class Application3 {

	public static void main(String[] args) {

		/*
		 * 데이터 자료형 별로 처리하는 기능을 추가한 보조스트림 DataInputStream / DataOutputStream
		 */

		DataOutputStream dout = null;

		try {
			dout = new DataOutputStream(new FileOutputStream("src/com/greedy/section03/filterstream/score.txt"));

			/* 파일에 자료형 별로 기록 */
			dout.writeUTF("홍길동");
			dout.writeInt(95);
			dout.writeChar('A');
			dout.writeUTF("이순신");
			dout.writeInt(87);
			dout.writeChar('B');
			dout.writeUTF("김철수");
			dout.writeInt(73);
			dout.writeChar('C');

			
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if (dout != null) {
					dout.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		DataInputStream din = null;
		
		try {
			din = new DataInputStream(
					new FileInputStream("src/com/greedy/section03/filterstream/score.txt"));
		
			/* 저장할 때의 자료형 단위와 순서 그대로 읽어올 때도 저장 했던 순서를 지켜서 읽어와야 한다. */
			while(true) {
				System.out.println(din.readUTF() + ", " + din.readInt() + ", " + din.readChar());
			}
		} catch (EOFException e) {	
			System.out.println("파일 읽기 완료!");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if(din != null) {
					din.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
				

	}
}
