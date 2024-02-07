package com.greedy.section03.filterstream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import com.greedy.section03.filterstream.dto.MemberDTO;

public class Application4 {
	public static void main(String[] args) {

		/*
		 * 객체 단위로 입출력을 하기 위한 스트림
		 * ObjectInputStream/ObjectOutputStream
		 */
		
		MemberDTO[] outputMembers = {
				new MemberDTO("user01", "pass01", "홍길동", "hong777@greedy.com" , 25, '남', 1250.7),
				new MemberDTO("user02", "pass02", "유관순", "korea31@greedy.com" , 16, '여', 1221.6),
				new MemberDTO("user03", "pass03", "이순신", "leesoonsin@greedy.com" , 22, '남', 1234.6)
		};
		
		ObjectOutputStream objOut = null;
		
		try {
			objOut = new ObjectOutputStream(new BufferedOutputStream(
					new FileOutputStream("src/com/greedy/section03/filterstream/testObjectStream.txt")));
			for(int i = 0; i <outputMembers.length; i++) {
				objOut.writeObject(outputMembers[i]);
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace(); 
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if(objOut != null) {
					objOut.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		MemberDTO[] inputMembers = new MemberDTO[outputMembers.length];
		
		ObjectInputStream objIn = null;
		BufferedInputStream bis = null;
		FileInputStream fis = null;
				
		try {
			fis = new FileInputStream("src/com/greedy/section03/filterstream/testObjectStream.txt");
			bis = new BufferedInputStream(fis);
			objIn = new ObjectInputStream(bis);
			
			
			int index = 0;
			while(true) {
				Object obj = objIn.readObject();
				
				if(obj instanceof MemberDTO) {
//					System.out.println((((MemberDTO)obj).getName()));
//					System.out.println((((MemberDTO)obj).getPoint()));
					inputMembers[index] = (MemberDTO)obj;
					index++;
				}
			}
		} catch (EOFException e) {
			System.out.println("파일 읽기 완료");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} finally {
			try {
				if(objIn != null) {
					objIn.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		for(MemberDTO m : inputMembers) {
			System.out.println(m);
		}
	}
}
