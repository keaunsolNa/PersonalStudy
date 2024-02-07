package com.greedy.section05.typecasting;

public class Application {
	public static void main(String[] args) {
	
		/* 데이터 형변환 */
		
		/* 1. 자동 형변환(묵시적 형변환): 컴파일러가 자동으로 수행해 주는 타입 변환 */
		/* 1-1. 작은 자료형에서 큰 자료형으로는 자동 형변환 된다. */
		byte bNum = 1;						// int형은 byte 또는 short로 자동 형변환 된다. (예외)
		short sNum = bNum;	
		int iNum = bNum + sNum; 			// int = byte + short = short + short -> int = short -> int = int
		long lNum = iNum;
		
		int num1 = 10;
		long num2 = 20;
		
//		int result1 = (num1 + num2);
		long result1 = num1 + num2;
		
		System.out.println("result1: " + result1);
		
		float fNum = 4.0f;
		double dNum = fNum;
		
		double result2 = fNum + dNum;
		
		System.out.println("result2: " + result2);
		
		/* 1-2. 문자형은 int형으로 자동 형변환 된다.(유니코드 표 외울 필요 없다.)  */ 
		char ch1 = 'a';
		int charNum = ch1;
		
		System.out.println("charNum: " + charNum);

		/* 1-3. 논리형은 형변환 규칙에서 제외된다. */
		boolean isTrue = true;
//		byte b = isTrue;
//		short s = isTrue;
//		int i = isTrue;
//		long l = isTrue;
//		char c = isTrue;
//		float f = isTrue;
//		double d = isTrue;
		
	}

}
