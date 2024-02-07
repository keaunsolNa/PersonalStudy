package com.greedy.section04.wrapper;

public class Application3 {
	public static void main(String[] args) {

		/* passing과 반대로 기본자료형 값을 문자열로 변경하는 경우도 할 수 있다. */
		String b = Byte.valueOf((byte)1).toString();
		String s = Short.valueOf((short)2).toString();
		String i = Integer.valueOf(4).toString();
		String l = Long.valueOf(8L).toString();
		String f = Float.valueOf(4.0f).toString();
		String d = Double.valueOf(8.0).toString();
		String b1 = Boolean.valueOf(true).toString();
		String c = Character.valueOf('a').toString();
		
		/* 문자열 합치기의 원리를 이용해 String으로 변환할 수도 있다. */
		String str = 123 + "";
		
		testMethod(1);
	}
	
	public static void testMethod(Object obj) {
		System.out.println(obj);
		/*testMethod(1)의 int가 AutoBoxing으로 Integer로 변한 뒤, 다형성으로 Object로 변환되는 것. */
	}
}
