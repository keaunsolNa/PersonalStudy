package com.greedy.section01.literal;

public class Application2 {
	public static void main(String[] args) {
		
		/*
		 *  출력 시 값을 직접 연산하여 출력할 수도 있다.
		 *   이 때 값의 형태에 따라 사용할 수 있는 연산자의 종류와 연산의 결과가 달라진다.
		 */
		
		/* 1. 숫자와 숫자의 연산 */
		/* 1-1. 정수와 정수의 연산 */
		/* 수학적으로 사용하는 사칙연산 외에, 추가적으로 나머지를 구하는 연산(mod 혹은 modulus)을 사용할 수 있다. */
		System.out.println("======== 정수와 정수의 연산 ========");
		System.out.println(123 + 456);					// 579
		System.out.println(123 - 23);					// 100
		System.out.println(123 * 10);					// 1230
		System.out.println(123 / 10);					// 정수 산술연산 정수는 정수가 나온다. 소수점 이하의 데이터는 버린다. 
		System.out.println(123 /(double)10);			// 정수 산술 연산 정수로 실수가 나오게 하려면 두 값 중 하나에 (double)을 붙이면 된다.
		System.out.println(123%10);						// 3, modulus == mod, 피제수 나누기 제수를 했을 때 나머지
		
		/* 1-2. 실수와 실수의 연산 */
		/* 실수끼리도 연산 시 수학에서 사용하는 사칙연산 외에, mod를 사용할 수 있다. */
		System.out.println("======== 실수와 실수의 연산 ========");
		System.out.println(1.23 + 1.23);				// 2.46
		System.out.println(1.23 - 1.23);				// 0.0
		System.out.println(1.23 * 1.23);				// 1.5129
		System.out.println(1.23 / 1.23);				// 1.0
		System.out.println(1.23 % 1.0);					// 컴퓨터는 지수함수로 계산하기에, 실수를 정확하게 인지하지 못 해 오차가 발생한다.
		
		/* 1-3. 정수와 실수의 연산 */
		/* 정수와 실수의 연산도 수학에서 사용하는 사칙연산 외에, mod를 사용할 수 있다. */
		System.out.println("======== 정수와 실수의 연산 ========");
		System.out.println(123 + 0.5);					//123.5
		System.out.println(123 - 0.5);					//122.5
		System.out.println(123 * 0.5);					//61.5
		System.out.println(123 / 0.5);					//246.0
		System.out.println(123 % 0.5);					//0.0
		
		/* 2. 문자의 연산 */
		/* 2-1. 문자와 문자의 연산 */
		/* 문자끼리의 연산도 사칙연산에 mod 연산까지 가능하다. */
		System.out.println("======== 문자와 문자의 연산========");
		System.out.println('a' + 'b');					// 195, 97 + 98과 같다. (아스키 코드) 
		System.out.println('a' - 'b');					// -1, 97 - 98과 같다
		System.out.println('a' * 'b');					// 9506, 97 * 98과 같다.
		System.out.println('a' / 'b');					// 0, 97 / 98과 같다.
		System.out.println('a' % 'b');					// 97, 97 % 98과 같다.
		
		/* 3. 문자열의 연산 */
		/* 3-1. 문자열과 문자열의 연산 */
		/* 문자열과 문자열은 '+' 연산 외에 다른 연산을 사용하지 못하면 문자열 합치기(이어붙이기)가 된다./
		System.out.println("======== 문자열과 문자열의 연산========");
		System.out.println("hello" + "hello");	 
//		System.out.println("hello" - "hello");			// 컴파일 에러 발생
//		System.out.println("hello" * "hello");			// 컴파일 에러 발생
//		System.out.println("hello" / "hello");			// 컴파일 에러 발생
//		System.out.println("hello" % "hello");			// 컴파일 에러 발생
		
		/* 3-2. 문자열과 다른 형태의 값 연산 */
		System.out.println("======== 문자열과 다른 값의 연산 ========");
		System.out.println("Hello" + 123);			
		System.out.println("Hello" + 123.456 );		
		System.out.println("Hello" + 'a');
		System.out.println("Hello" + true);
		System.out.println('a' + 'b');
		
		/* 특이 케이스 */
		System.out.println(123 + 124.0 + "hello" + true + (124 + 124.0));
		// 1. 123 + 124.0 + "hello" + true +248.0
		// 2. 123 + 247.0 + "hello" + "true" + 128.0	// 문자열 이후는 각 항을 문자열로 처리해서 이어 붙인다.
		// 3. 247.- + "hellotrue248.0"
		// 4. "247.0" + "hellptrue248.0"				// 문자열이 포함된 연산식은 문자열 이전의 항도 문자열로 바꾼다.
		// 5. "247.0hellotrue248.0"
	
	
	}	
}
