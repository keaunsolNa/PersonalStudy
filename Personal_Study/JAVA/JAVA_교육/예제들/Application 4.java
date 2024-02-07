package com.greedy.section02.uses;

import java.util.Scanner;

public class Application {
	public static void main(String[] args) {
		Scanner sc = new Scanner(System.in);
		
		MemberManager mm = new MemberManager();
		
		do {
			System.out.println(" ====== 회원 관리 프로그램 ======");
			System.out.println("1. 회원 등록");
			System.out.println("2. 회원 전체 조회");
			System.out.println("9. 프로그램 종료");
			System.out.println("메뉴 선택: ");
			int no = sc.nextInt();
			
			switch(no) {
				case 1 : mm.signUpMembers();break;
				case 2 : mm.printAllmembers();break;
				case 9 : System.out.println("프로그램을 종료합니다.");
					return;
				default : System.out.println("잘못된 번호를 입력하셨습니다.");
			}
		} while(true);
	}

}
