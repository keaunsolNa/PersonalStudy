package com.greedy.section01.array;

import java.util.Scanner;

public class Application2 {
	public static void main(String[] args) {

		/* 배열의 사용 방법 */
		
		/* 선언은 stack에 배열의 주소를 보관할 수 있는 공간을 만드는 것이다. */
		int[] iarr;
		char cArr[];		//왠만하면 []는 자료형 다음에 붙여서 쓰고 이렇게 쓰지 말자.
		String[] strArr;
		
		/*
		 * 선언한 참조형 변수(레퍼런스 변수)에 배열을 할당하여 대입할 수 있다.
		 * new 연선자는 heap 영역에 공간을 할당하고 발생한 주소값을 반환하는 연산자이다.
		 * 발생한 주소를 참조형 변수(레퍼런스 변수)에 저장하고 이것을 참조하여 사용하기 때문에
		 * 참조 자료형(reference type)이라고 한다.
		 */
		
		iarr = new int[5];
		cArr = new char[10];

		/* 위의 선언과 할당을 동시에 할 수도 있다. */
		int[] iArr2 = new int[5];
		char cArr2[] = new char[10];
		
		/*
		 *  hashCode() : 일반적으로 객체의 주소를 의미하는 수치를
		 *  			 10진수로 변환하여 객체의 고유한 정수값을 반환한다.
		 */
		System.out.println("iArr2의 hashCode: " + iArr2.hashCode());
		System.out.println("iArr2의 hashCode: " + cArr2.hashCode());
		
		System.out.println("iArr2의 길이: " + iArr2.length);
		System.out.println("cArr2의 길이: " + cArr2.length);
		
		/* 스캐너를 통해 입력받은 정수로 배열의 길이를 지정하여 배열을 할당할 수도 있다. */
		Scanner sc = new Scanner(System.in);
		
		System.out.println("새로 할당 할 배열의 길이를 입력하세요: ");
		int size = sc.nextInt();
		
		double[] dArr = new double[size];
		System.out.println("dArr의 hashCode: " + dArr.hashCode());
		
		System.out.println("dArr의 길이: " + dArr.length);
		
		dArr = new double[30];
		System.out.println("수정 후 dArr의 길이 hashCode: " + dArr.hashCode());
		
		System.out.println("dArr의 길이: " + dArr.length);
		/*
		 * 한 번 할당된 배열은 원래 따로 지우는 작업을 코드로 할 수 없지만
		 * 레퍼런스 변수가 더 이상 주소를 참조할 수 없게 된 배열은
		 * 일정 시간이 지난 후 heap의 old영역으로 이동하여 GC(가비지 컬렉터)가 삭제 시킨다.
		 * 한 번 찾아갈 수 있는 주소값을 잃어버린 배열은 다시 참조 불가능하다.
		 */
		
		/*
		 * NullPointerExceptiono 발생함
		 * 아무것도 참조하지 않고 null이라는 특수한 값을 참조하고 있는 경우(아무것도 참조하고 있지 않을 경우)
		 * 참조연산자를 사용하게 될 때 발생하는 에러이다.
		 */
		dArr = null;
		System.out.println("삭제 후 dArr의 길이: " + dArr.length);

	}

}
