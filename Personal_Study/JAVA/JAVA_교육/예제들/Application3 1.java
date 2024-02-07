package com.greedy.section02.variable;

public class Application3 {
	public static void main(String[] args) {
		
		/* 변수의 명명 규칙 */
		/*
		 * 변수의 이름을 지을 때 아무렇게나 짓는 것이 아닌 정해진 규칙이 있다.
		 * 실무적으로는 굉장히 중요하기 때문에 반드시 숙지해야 하고,
		 * 또한, 규칙에 맞는 올바른 변수명을 짓는 것이 좋은 프로그래밍을 위한 첫 단추이다.
		 */
		
		/* 1. 컴파일 에러를 발생 시키는 규칙 */
		int age = 20;
//		int age = 30;		// 같은 범위 내에 동일한 변수명을 가지므로 에러가 발생함
		
//		int true = 1;		// 예약어 사용 불가
//		int for = 20;		// 예약어 사용 불가
		
		int aGe = 10;		// 위에서 만든 age와 다른 것으로 취급
		int truE = 10;		// 예약어가 아닌 것으로 취급
		
//		int 1age = 20;		// 숫자로 처음 시작해서 에러 발생
		int age1 = 20;		// 숫자가 처음에만 시작하지 않으면 섞어 써도 무방
		
//		int sh@rp = 20;		// '@'는 에러 발생
		int _age = 20;		// 언더스코어(_)는 사용 가능
		int $harp = 20;		// $는 사용 가능
		
		/* 2. 컴파일 에러를 발생 시키지는 않지만 개발자들끼리의 암묵적인 규칙 */
		int fdsafdsajklrqwepiurqwepovnzxkorfewmrqweobjdsaflrew;		// 적당히 쓰자.
		
		int maxAge = 80;	// 합성어(단어 두 개 이상)일 경우 낙타봉 표기법 사용(camel-case)
		int minAge = 11;
		
		String user_name;	// 가급적 쓰지 말자
		String userName;
		
		/* 전형적인 변수명이 있다면 가급적 사용 */
		int sum = 0;
		int max = 10;
		int min = 0;
		int count = 1;
		
		/* 명사형으로 작성 */
		String goHome;		// 동사형이 가능하긴 하지만 가급적 명사형으로 짓는다.
		String home;
		
		/* boolean은 긍정 의문문으로 네이밍 한다. */
		boolean isAlive = true;
		boolean isDead = false;
		boolean isNotAlive = false;		// 부정 의문문은 사용하지 않는다.
	}
}






