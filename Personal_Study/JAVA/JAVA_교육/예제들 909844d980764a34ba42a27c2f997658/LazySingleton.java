package com.greedy.section06.singleton;

public class LazySingleton {

	/* 클래스가 초기화 되는 시점에는 정적 필드를 선언해 두고 null로 초기화 된다. */
	private static LazySingleton lazy;
	
	/* 싱글톤 패턴은 생성자 호출을 통한 외부에서 인스턴스를 생성하는 것을 제한한다. */
	private LazySingleton() {}

	/* public 메소드로 인스턴스를 반환하도록 한다. */
	public static LazySingleton getInstance() {
		
		/*
		 * 인스턴스를 생성한 적이 없는 경우(getInstance메소드가 호출이 한번도 안된 경우)
		 * 인스턴스를 생성해서 반환하고 생성한 인스턴스가 있는 경우 만들어 둔 인스턴스를
		 * 반환한다.
		 */
		if (lazy == null) {
			
			/* 인스턴스를 생성한 적이 없는 경우 새로운 인스턴스 생성 */
			lazy = new LazySingleton();
		}
		
		return lazy;
	}
}
