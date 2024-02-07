package com.greedy.section02.encapsulation.problem1;

public class Application {

	public static void main(String[] args) {
		
		/* 필드에 바로 접근이 가능할 때 발생할 수 있는 문제점 1 */
		
		/* 1번 몬스터 생성 */
		
		Monster monster1 = new Monster();
		monster1.name = "두치";
		monster1.hp = 200;
		
		/* 몬스터 정보 출력 */
		System.out.println("monster1 name: " + monster1.name);
		System.out.println("monster1 name: " + monster1.hp);
		
		
		/* 2번 몬스터 생성 */
		Monster monster2 = new Monster();
		monster2.name = "뿌꾸";
		monster2.hp = -200;					// hp를 넣어서는 안될 음수로 지정하겠다.
		
		/* 몬스터 정보 출력 */
		System.out.println("monster2 name: " + monster2.name);
		System.out.println("monster2 name: " + monster2.hp);
		
		/*
		 * 검증되지 않은 값이 들어가는 것에 대해서 이를 해결하기 위해 Monster 클래스로
		 * 가서 수정하자.
		 */
		
		/* 3번 몬스터 생성 */
		Monster monster3 = new Monster();
		monster3.name = "드라큘라";
		monster3.setHp(200);
		
		/* 몬스터 정보 출력 */
		System.out.println("monster3 name: " + monster3.name);
		System.out.println("monster3 name: " + monster3.hp);
		
		/* 4번 몬스터 생성 */
		Monster monster4 = new Monster();
		monster4.name = "프랑켄슈타인";
		monster4.setHp(-1000);
		
		/* 몬스터 정보 출력 */
		System.out.println("monster4 name: " + monster4.name);
		System.out.println("monster4 name: " + monster4.hp);
		
	
	}

}
