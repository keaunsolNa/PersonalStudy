package com.greedy.section01.method;

public class Application9 {

	public static void main(String[] args) {
		
		/* Calculator 클래스에 메소드를 만들자. */
		int first = 20;
		int second = 50;
		
		/* non -static 메소드일 경우 */
		 
		 Calculator calc = new Calculator();
		 int min = calc.minNumberOf(first, second);
		 
		 System.out.println("두 수 중 최소값은: " + min);

		 /* static 메소드일 경우 */
		 int max = Calculator.maxNumberOf(first, second);
		 
		 System.out.println("두 수 중 최대값은: " + max);
		 
		
		
		
	}

}
