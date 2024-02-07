package com.greedy.section02.encapsulation.problem4;

public class Application {

	public static void main(String[] args) {
		Monster m = new Monster();
		
		/* Monster 클래스에 private 적용 후 직접 접근이 안됨을 확인 */
//		System.out.println(m.kinds);
//		System.out.println(m.hp);

		Monster monster1 = new Monster();
		monster1.setInfo("프랑켄슈타인");
		monster1.setHp(200);
		
		System.out.println(monster1.getInfo());			//첫 번째 private 필드값
		System.out.println(monster1.getHp());			//두번째 private 필드값
		System.out.println(monster1.getFields());
	}

}
