package com.greedy.section04.wrapper;

public class Application2 {
	public static void main(String[] args) {

		/* passing: 문자열(String) 값을 기본자료형 값으로 변경하는 것을 passing이라고 한다. */
//		byte b = Byte.parseByte("1");
//		short s = Short.parseShort("2");
//		int i = Integer.parseInt("4");
//		long l = Long.parseLong("8");				// 8L은 안됨.
//		float f = Float.parseFloat("4.0f");			// 4.0f는 됨.
//		double d = Double.parseDouble("8.0");
//		boolean b1 = Boolean.parseBoolean("true");
		char c = "님".charAt(0);						// char형은 String에서 제공하는 charAt()을 써야한다.

		byte b = Byte.valueOf("1");
		short s = Short.valueOf("2");
		int i = Integer.valueOf("4");
		long l = Long.valueOf("8");				// 8L은 안됨.
		float f = Float.valueOf("4.0f");			// 4.0f는 됨.
		double d = Double.valueOf("8.0");
		boolean b1 = Boolean.valueOf("true");
		
		System.out.println(l);
		System.out.println(f);
	}
}
