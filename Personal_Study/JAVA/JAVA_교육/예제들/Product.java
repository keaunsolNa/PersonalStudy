package com.greedy.section08.initblock;

public class Product {

	/* 0. JVM이 각각 초기화를 해줌 */
//	private String name;
//	private int price;
//	private static String brand;

	/* 1. 명시적 초기화를 통해 초기화를 할 수 있다. */
	private String name = "갤럭시";
	private int price = 10000000;
	private static String brand = "샘숑";

	
	{
		name = "사이언";
		Product.brand = "헬지";
		System.out.println("인스턴스 초기화 블럭 동작함...");
	}
	
	static {						// static 영역만 초기화 한다.
//		name = "아이뽕";
		Product.brand = "사과";
		System.out.println("정적 초기화 블럭 등장함...");
	}
	
	public Product() {
		name = "투명폰";
		System.out.println("객체 생성한답니다.");
	}

	@Override
	public String toString() {
		return "Product [name=" + this.name + ", price=" + this.price
				+ ", brand=" + Product.brand + "]";
	}
}


