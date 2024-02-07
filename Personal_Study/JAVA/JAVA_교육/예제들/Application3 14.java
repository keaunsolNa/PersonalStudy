package com.greedy.section02.stream;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class Application3 {
	public static void main(String[] args) {

		/* FileOutputStream */
		/*
		 * 프로그램의 데이터를 파일로 내보내기 위한 용도의 스트림이다.
		 * 1바이트 단위로 데이터를 처리한다.
		 */
		
		FileOutputStream fout = null;
		
		try {
			
			/*
			 * OutputStream의 경우 대상 파일이 존재하지 않으면 파일을 자동으로 생성해 준다.
			 * (단, 중간 디렉토리 경로가 잘못 되면 FileNotFoundException이 발생한다. )
			 * 두 번째 인자로 true를 전달하면 이어쓰기가 가능하다. false는 이어쓰기가 아닌 덮어쓰기이며
			 * 두 번째 인자를 따로 주지 않아도 덮어쓰기로 이루어진다. 
			 */
//			fout = new FileOutputStream("src/com/greedy/section02/stream/testOutputStream.txt");
			fout = new FileOutputStream("src/com/greedy/section02/stream/testOutputStream.txt", true);
		
			fout.write(97);			// 소문자 'a'를 출력 스트림을 통해 파일에 내보내기 함.
			fout.write('b');
			
			byte[] bar = new byte[] {98, 99, 100, 10, 101, 102};	//  10은 개행문자이다. (엔터)
			fout.write(bar);
			
			fout.write(bar, 1, 3);		// byte 배열에서 1번 인덱스부터 3의 길이만큼 파일에 내보내기(cd엔터 기록 됨)
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if(fout != null) {
					fout.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
	}

}
