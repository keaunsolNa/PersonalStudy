package com.greedy.section02.encapsulation.problem3;

public class Application {
	public static void main(String[] args) {
		
		/* 앞에서 발생한 두 가지 문제점을 해결해 보자. */
		/* 하지만 여전히 직접 접근은 가능한 상태이다. */
		
		/* 몬스터 객체를 여러 개 생성해 보자. */
		
		Monster monster1 = new Monster();
		monster1.setInfo("드라큘라");
		monster1.setHp(100);
		
		Monster monster2 = new Monster();
		monster2.setInfo("프랑켄슈타인");
		monster2.setHp(-100);
	
		Monster monster3 = new Monster();
		monster3.setInfo("늑대인가");
		monster3.setHp(200);
		
		
	}

}
