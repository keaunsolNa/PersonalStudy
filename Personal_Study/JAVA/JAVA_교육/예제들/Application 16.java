package com.greedy.section02.superkeyword;

import java.util.Date;

public class Application {
	public static void main(String[] args) {
		
		/* super. 과 super()에 대해 알아보자. */
		
		/* ProductDTO 기본 생성자로 인스턴스 생성 후 정보 출력 */
		ProductDTO product1 = new ProductDTO();
		System.out.println(product1.toString());
		System.out.println(product1);				// println이 toString()을 생략해도 실행해 줌
		
		/* ProductDTO 모든 필드를 초기화하는 생성자로 인스턴스 생성 후 정보 출력 */
		ProductDTO product2 = 
				new ProductDTO("S-01234", "삼성", "갤럭시Z폴드2", 2398000, new java.util.Date());
		System.out.println(product2);
		
		/* ComputerDTO 기본 생성자로 인스턴스 생성 후 정보 출력 */
		ComputerDTO computer1 = new ComputerDTO();
		System.out.println(computer1);
		
		/* ComputerDTO가 추가적으로 가진 필드를 초기화 하는 생성자로 인스턴스 생성 후 정보 출력 */
		ComputerDTO computer2 = new ComputerDTO("퀄컴 스냅드래곤", 512, 12, "안드로이드");
		System.out.println(computer2);
		
		/* ComputerDTO 부모필드도 포함한 모든 필드를 초기화 하는 생성자로 인스턴스 생성 후 정보 출력 */
		ComputerDTO computer3 = new ComputerDTO("S-01234", "삼성", "갤럭시Z폴드2", 2398000, new java.util.Date(),
												"퀄컴 스냅드래곤", 512, 12, "안드로이드");
		System.out.println(computer3);
		
	}

}
