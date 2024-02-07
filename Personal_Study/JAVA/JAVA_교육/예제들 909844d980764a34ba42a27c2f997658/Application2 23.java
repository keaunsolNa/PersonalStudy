package com.greedy.section02.stream;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

public class Application2 {
	public static void main(String[] args) {

		/* FileReader */
		/*
		 * FileInputStream과 사용하는 방법이 거의 동일하다. 
		 * 단, byte단위가 아닌 character단위(인코딩 방식에 맞춰서)로 인지하고 읽어들인다는 부분이 차이점이다.
		 * 따라서, 2바이트이던 3바이트이던 글자 단위로 읽어오기 때문에 한글을 정상적으로 읽어올 수 있다.
		 */
		
		FileReader fr = null;
		
		try {
			fr = new FileReader("src/com/greedy/section02/stream/testReader.txt");

			/* 여기는 한글로 작성을 해도 안 깨지고 읽어올 수 있다. */
//			int value;
//			while((value = fr.read()) != -1) {
//				System.out.println((char) value);
//			}
			
			/* utf-8(3바이트당 한문자)일 때는 파일의 크기가 6바이트라면 실제로는 문자 2개가 저장된 것이다. 
			 * 따라서 length()를 쓰면 byte 단위로 나오므로 (byte / 3)을 하면 문자의 갯수가 나오게 된다.
			 */
			char[] cArr = 
					new char[(int)new File("src/com/greedy/section02/stream/testReader.txt").length()/3];
			
			fr.read(cArr);
			
			
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if(fr != null) {
					fr.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

}
