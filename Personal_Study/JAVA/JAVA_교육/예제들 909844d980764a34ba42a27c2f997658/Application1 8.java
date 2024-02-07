package com.greedy.section02.package_and_import;

public class Application1 {

	public static void main(String[] args) {

		/*
		 * 패키지
		 * 서로 관련 있는 클래스 또는 인터페이스 등을 모아 하나의 묶음(그룹)으로 단위를 구성하는 것
		 * 같은 패키지 내에서는 동일한 이름의 클래스를 만들 수 없지만, 패키지가 다르면 동일한 이름을
		 * 가진 클래스를 만들 수 있다.
		 * 지금까지 클래스명에 패키지명을 함께 사용하지 않은 이유는 동일한 패키지 내에서 사용했기 때문이다.
		 * 그렇기 때문에 서로 다른 패키지에 존재하는 클래스를 사용하는 경우에는
		 * 클래스명 앞에 패키지명을 명시해서 풀 클래스명을 작성해야 한다. 
		 */
		
		int first = 30;
		int second = 20;
		
		/* Calculator*(다른 패키지에 있는)에 있는 non-satatic 메소드 호출 */
		com.greedy.section01.method.Calculator calc = 
				new com.greedy.section01.method.Calculator();

		int min = calc.minNumberOf(first, second);
		System.out.println("30과 20 중에 더 작은 값은: " + min);
		
		/* Calculator(다른 패키지에 있는)에 있는 static 메소드 호출 */
		int max = com.greedy.section01.method.Calculator.maxNumberOf(first, second);
		System.out.println("30과 20중에 더 큰 값은: " + max);
	}

}
