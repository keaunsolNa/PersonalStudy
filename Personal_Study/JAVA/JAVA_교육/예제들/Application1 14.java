package com.greedy.section04.wrapper;

public class Application1 {
	public static void main(String[] args) {

		/* Wrapper 클래스 */
		/*
		 * 상황에 따라 기본 타입의 데이터를 인스턴스화 해야 하는 경우들이 발생한다.
		 * 이 때 기본 타입의 데이터를 먼저 인스턴스로 변환 후 사용해야 하는데
		 * 8가지에 해당하는 기본 타입의 데이터를 인스턴스화 할 수 있도록 하는
		 * 클래스를 래퍼 클래스(Wrapper Class)라고 한다.
		 */
		
		/*
		 * 기본 타입 								래퍼 클래스
		 * byte			----->					Byte
		 * short		boxing					Short
		 * int									Integer		(v)
		 * long									Long
		 * float								Float
		 * double		<-----					Double
		 * char			unboxing				Character	(v)
		 * boolean								Boolean
		 */
		
		/*
		 *  박싱(Boxing)과 언박싱(UnBoxing)
		 *  기본 타입을 래퍼클래스의 인스턴스로 인스턴스화 하는 것을 박싱(Boxing)이라고 하며,
		 *  래퍼클래스 타입의 인스턴스를 기본 타입으로 변경하는 것을 언박싱(UnBoxing)이라고 한다.
		 */
		int intValut = 20;
		Integer boxingNumber1 = new Integer(intValut);			// 인스턴스화
		Integer boxingNumber2 = Integer.valueOf(intValut);		// static 메소드 이용
		/* 위의 방법은 언제 사라질지 모르니 가급적 아래의 방법을 사용하자 */

		int unBoxingNumber1 = boxingNumber1.intValue();			// intValue() 이용
		
		/*
		 * 오토 박싱(AutoBoxing)과 오토 언박싱(AutoUnBoxing)
		 * JDK 1.5부터는 박싱과 언박싱이 필요한 상황에서 자바 컴파일러가 이를 자동으로 처리해 준다.
		 */
		Integer boxingNumber3 = intValut;
				;
		int UnBoxingNumber1 = boxingNumber3;
		
		/* Wrapper 클래스 값 비교 */
		int iNum = 20;
		Integer integerNum1 = new Integer(20);
		Integer integerNum2 = new Integer(20);
		Integer integerNum3 = (20);
		Integer integerNum4 = (20);
		
		/* 기본 타입과 래퍼클래스 타입도 == 연산으로 비교 가능하다. (기본자료형 값과 래퍼클래스가 지닌 필드값 비교0) */
		System.out.println("int와 Integer 비교: " + (iNum == integerNum1));		// true
		System.out.println("int와 Integer 비교: " + (iNum == integerNum3));		//true
		
		
		/* 생성자를 이용해 생성한 인스턴스 간에는 == 으로 비교 시 주소값 비교를 하게 된다. */
		System.out.println("Integer와 Integer 비교: "  + (integerNum1 == integerNum2));		// false
		System.out.println("Integer와 Integer 비교: "  + (integerNum1 == integerNum3));		// false
		System.out.println("Integer와 Integer 비교: "  + (integerNum3 == integerNum4));		// true

		/* Wrapper 클래스의 Integer 객체의 주소값 확인 */
		System.out.println(System.identityHashCode(integerNum1));
		System.out.println(System.identityHashCode(integerNum2));
		System.out.println(System.identityHashCode(integerNum3));
		System.out.println(System.identityHashCode(integerNum4));
		
		
		
	}

}
