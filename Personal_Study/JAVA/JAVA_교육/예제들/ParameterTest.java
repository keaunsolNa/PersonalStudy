package com.greedy.section05.overloading.parameter;

import java.util.Arrays;

public class ParameterTest {

	public void testPrimitiveTypeParameter(int num) {
		System.out.println("매개변수로 전달 받은 값: " + num);
	}

	public void testPrimitiveTypeArrayParameter(int[] iArr) {
		System.out.println("매개변수로 전달 받은 값: " + Arrays.toString(iArr));
	}

	public void testClassTypeParameter(Rectangle r1) {
		System.out.println("매개변수로 전달 받은 값: " + r1);

		r1.setWidth(100);
		r1.setHeight(200);
		
		r1.calcArea();
		r1.calcRound();
	}

}
