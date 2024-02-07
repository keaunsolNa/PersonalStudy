package com.greedy.section05.overloading.parameter;

public class Application {
	public static void main(String[] args) {
		
		ParameterTest pt = new ParameterTest();
		
		int num = 20;
		pt.testPrimitiveTypeParameter(num);
		
		int[] iArr = new int[] {1, 2, 3, 4, 5};
		pt.testPrimitiveTypeArrayParameter(iArr);			//배열이 아닌, 배열의 주소값을 넘기는 것.
		
		Rectangle r1 = new Rectangle(12.5, 22.5);
//		System.out.println(r1.toString());
		System.out.println(r1);								//println 은 출력하고자 하는 인스턴스가 가진 toString()메소드를 자동 실행함.
		
		pt.testClassTypeParameter(r1);
	}

}
