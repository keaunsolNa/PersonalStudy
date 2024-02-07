package com.greedy.section02.string;

public class Application4 {
	public static void main(String[] args) {
		
		/* 
		 * 이스케이프(escape) 문자
		 * \n 
		 * \t
		 * \'
		 * \\
		 * \'
		 * 
		 */
		
		/* 개행 문자 */
		System.out.println("안녕하세요.\n저는 김자바입니다.");
		
		/* 탭 문자 */
		System.out.println("안녕하세요. \t저는 안자바입니다.");

		/* 홀 따움표 문자 */
		System.out.println("안녕하세요. 저는 '뭘자바'입니다.");
		
		/* 쌍 따움표 문자 */
		System.out.println("안녕하세요 저는 \"다자바\"입니다.");

		/*슬래쉬 문자 */
		System.out.println("안녕하세요 저는 \\못자바\\입니다.");
	
		/* 문자 리터럴에서 '을 쓰고 싶으면 이스케이프 문자를 써야 한다. */
		System.out.println('\'');
		
		
		
	}

}
