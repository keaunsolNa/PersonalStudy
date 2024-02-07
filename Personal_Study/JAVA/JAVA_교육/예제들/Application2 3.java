package com.greedy.section05.typecasting;

public class Application2 {
	public static void main(String[] args) {
		
		/* 2. 강제 형변환 */
		/* 2-1. 큰 자료형에서 작은 자료형으로 변경 시 강제 형변환이 필요하다. */
		long lNum = 0;
//		int iNum = lNum;			// 데이터 손실 가능성을 컴파일러가 알려줌 (컴파일 에러 발생)
		int iNum = (int)lNum;
		
		/* 2-2. 실수를 정수로 변경 시 강제 형변환이 필요하다. */
		float fNum2 = 4.0f;
		long lNum2 = (long)fNum2;	// 소수점 이하 버림
		
		/* 2-3. 문자형을 int 미만 크기의 변수에 저장할 때 강제 형변환이 필요하다. */
		char ch = 'a';
		byte bNum2 = (byte)ch;
		short sNum2 = (short)ch;			// char은 음수가 없고, short는 음수가 있다.
		
		
		
		
	}
}
