package com.greedy.section02.variable;

public class Application2 {
	public static void main(String[] args) {
		
		/* 변수를 사용하기 위한 방법 */
		/*
		 * 1. 변수를 준비한다. (선언)
		 * 2. 변수에 값을 대입한다. (값 대입 혹은 초기화)
		 * 3. 변수를 사용한다. 
		 */
		
		/* 1. 변수를 준비한다. (선언) */
		/* 자료형 변수형; */
		
		/*
		 * 자료형이란?
		 * 다양한 값의 리터럴 형태별로 어느 정도의 표기르 하나의 값으로 취미할 것인지 미리
		 * compiler와 약속한 키둬드
		 * ex)	앞에서 서술한 int 자료형은 정수를 4byte만큼을 읽어서 하나의 값으로 취급하겠다는 약속이다.
		 * 		이러한 자료형은 기본자료형(Primitive Type)과 참조자료형(Reference Type)으로 나뉘어 진다.
		 * 		그 중 기본자료형은 8가지이다.
		 */
		
		/* 1-1. 숫자를 취급하는 자료형 */
		byte bNum;			// 1byte
		short sNum;			// 2byte
		int iNum;			// 4byte
		long lNum;			// 8byte
		
		float fNum;			// 4byte
		double dNum;		// 8byte
		
		/* 1-2. 문자를 취급하는 자료형 */
		char chl; 			// 2byte
		
		/* 1-3. 논리값을 취급하는 자료형 */
		boolean isTrue; // 1byte, 긍정과 의문문 형태의 변수명 사용
		
		/* 여기까지가 8가지 기본 자료형(Primitive Type)이라고 한다. */

		/* 1-4. 문자열을 취급하는 참조 자료형 */
		String str;			// 주소 값을 담는 공간이고, 엄밀히 말하면 4byte이다.
		
		/* 2. 변수에 값을 대인한다. (값 대입 및 초기화(선언 후 처음 대입) */
		/*
		 * 위에서 한 변수 선언은 메모리에 값을 저장하기 위해 공간(+default값)을 생성해 둔 상태이다.
		 * 그 공간에 대입연산자(=)을 이용하여 자료형에 저장하기로 한 형태의 값을 저장할 수 있다.
		 * 만약, 약속 내용과 다른 값을 대입하려고 하면 compiler는 에러를 발생 시킨다. (컴파일 에러)
		 * 
		 * 대입 연산자의 실행 방향은 오른쪽에서 왼쪽이다.
		 * 즉, 오른쪽에 있는 값을 왼쪽의 공간에 대입한다는 의미이다.
		 * 
		 * 공간 = 변수가 가질 값;
		 * =는 우측을 좌측의 공간에 대입한다.
		 */
		
		/* 2-1. 정수를 취급하는 자료형에 값 대입 */
		bNum = 12;		//허용, 예외. Java는 int값을 기본값으로 사용하기에, int값 보다 작은 byte값과 short값의 경우 int 값이 byte, short 값으로 변경되며 에러가 발생해야 하나, 잦은 오류로 예외사항으로 지정했다.
		sNum = 3;		//허용, 예외.
		iNum = 4;		
		lNum = 13L;
		
		fNum = 4.0f;
		dNum = 8.0;
		
		System.out.println(bNum + sNum + iNum + lNum);
	}

}
